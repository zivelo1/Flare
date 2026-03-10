//! Adaptive power management for BLE mesh networking.
//!
//! BLE scanning and advertising are the dominant battery consumers in a mesh app.
//! This module provides an adaptive duty cycling strategy that balances discovery
//! speed against battery life by transitioning between power tiers based on
//! network activity and device battery state.
//!
//! ## Research basis
//!
//! - Android BLE scan modes map to fixed duty cycles:
//!   LOW_LATENCY: 100% duty (5120ms window / 5120ms interval)
//!   BALANCED:    ~25% duty (1024ms / 4096ms)
//!   LOW_POWER:   ~0.5% duty (512ms / 5120ms × 1/10)
//!
//! - iOS CoreBluetooth background scanning:
//!   Foreground: ~100ms scan every 1.1s (effective ~9% duty)
//!   Background: one scan every ~4 minutes (with service UUID filter)
//!
//! - Empirical BLE power draw (Nexus 5X reference):
//!   LOW_LATENCY scan: ~75mA
//!   BALANCED scan: ~25mA
//!   LOW_POWER scan: ~8mA
//!   No scan (idle): ~3mA
//!
//! The adaptive strategy reduces average draw to ~10-15mA by spending most
//! time in LOW_POWER mode and bursting to BALANCED/LOW_LATENCY only when needed.

use std::sync::Mutex;

/// Power tier determines BLE scan mode and advertising interval.
/// Each tier has specific scan and advertise parameters optimized for
/// its use case.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PowerTier {
    /// Maximum discovery speed. Used during active message send/receive
    /// or when a new peer was just discovered and key exchange is happening.
    /// Android: SCAN_MODE_LOW_LATENCY, advertise LOW_LATENCY
    /// Duration: limited to `high_duration_seconds` then auto-downgrades.
    High,

    /// Normal operation with peers in range. Good balance of discovery
    /// speed and battery consumption.
    /// Android: SCAN_MODE_BALANCED, advertise BALANCED
    /// Used when: peers are present but no active data exchange.
    Balanced,

    /// Low power scanning for peer discovery when no peers are nearby.
    /// Android: SCAN_MODE_LOW_POWER, advertise LOW_POWER
    /// Used when: no peers discovered in recent scan cycles.
    LowPower,

    /// Minimal scanning to preserve battery. Used when battery is critically
    /// low or user has manually selected battery saver mode.
    /// Scans in short bursts with long intervals between.
    UltraLow,
}

/// Configuration for adaptive power management.
/// All timing values are configurable — no hardcoded constants in business logic.
pub struct PowerConfig {
    // ── Transition thresholds ──────────────────────────────────────
    /// Seconds of inactivity (no data sent/received) before downgrading
    /// from High to Balanced tier. Default: 10 seconds.
    pub high_inactivity_threshold_secs: u32,

    /// Seconds without any peer discovery before downgrading from
    /// Balanced to LowPower tier. Default: 60 seconds.
    pub balanced_no_peers_threshold_secs: u32,

    /// Maximum continuous seconds in High tier before forced downgrade.
    /// Prevents accidental battery drain if the app stays active. Default: 30 seconds.
    pub high_duration_limit_secs: u32,

    /// Battery percentage threshold below which the system forces UltraLow mode.
    /// Default: 15%.
    pub critical_battery_percent: u8,

    /// Battery percentage threshold below which High tier is disabled.
    /// The system will use Balanced as its maximum tier. Default: 30%.
    pub low_battery_percent: u8,

    // ── Scan parameters per tier ───────────────────────────────────
    /// Scan window in milliseconds for each tier.
    /// These map to actual Android ScanSettings or iOS scan durations.
    pub scan_window_ms: TierParams<u32>,

    /// Scan interval in milliseconds for each tier.
    pub scan_interval_ms: TierParams<u32>,

    /// Advertising interval hint in milliseconds for each tier.
    /// On Android this maps to AdvertiseSettings mode; on iOS it's a hint.
    pub advertise_interval_ms: TierParams<u32>,

    // ── Burst scanning ─────────────────────────────────────────────
    /// In LowPower and UltraLow tiers, scanning uses burst mode:
    /// scan for `burst_scan_duration_ms` then sleep for `burst_sleep_duration_ms`.
    /// This provides periodic discovery while minimizing average power draw.
    pub burst_scan_duration_ms: TierParams<u32>,
    pub burst_sleep_duration_ms: TierParams<u32>,
}

/// Per-tier parameter values.
#[derive(Debug, Clone, Copy)]
pub struct TierParams<T: Copy> {
    pub high: T,
    pub balanced: T,
    pub low_power: T,
    pub ultra_low: T,
}

impl<T: Copy> TierParams<T> {
    pub fn get(&self, tier: PowerTier) -> T {
        match tier {
            PowerTier::High => self.high,
            PowerTier::Balanced => self.balanced,
            PowerTier::LowPower => self.low_power,
            PowerTier::UltraLow => self.ultra_low,
        }
    }
}

impl Default for PowerConfig {
    fn default() -> Self {
        PowerConfig {
            high_inactivity_threshold_secs: 10,
            balanced_no_peers_threshold_secs: 60,
            high_duration_limit_secs: 30,
            critical_battery_percent: 15,
            low_battery_percent: 30,

            // Scan window: how long the radio listens per scan cycle
            scan_window_ms: TierParams {
                high: 4096,     // Near-continuous scanning
                balanced: 1024, // 25% duty cycle
                low_power: 512, // Short listen windows
                ultra_low: 256, // Minimal listen
            },

            // Scan interval: time between scan windows
            scan_interval_ms: TierParams {
                high: 4096,       // 100% duty (window == interval)
                balanced: 4096,   // ~25% duty (1024/4096)
                low_power: 10240, // ~5% duty (512/10240)
                ultra_low: 20480, // ~1.25% duty (256/20480)
            },

            // Advertising interval
            advertise_interval_ms: TierParams {
                high: 160,       // ~100ms (fastest BLE spec allows for connectable)
                balanced: 400,   // ~250ms
                low_power: 1600, // ~1s
                ultra_low: 3200, // ~2s
            },

            // Burst scan duration (only used in LowPower and UltraLow)
            burst_scan_duration_ms: TierParams {
                high: 0,         // Not used — continuous scanning
                balanced: 0,     // Not used — continuous scanning
                low_power: 5000, // Scan for 5 seconds
                ultra_low: 3000, // Scan for 3 seconds
            },

            // Burst sleep duration between scan bursts
            burst_sleep_duration_ms: TierParams {
                high: 0,
                balanced: 0,
                low_power: 25000, // Sleep 25 seconds between bursts (5s/30s = ~17% active)
                ultra_low: 57000, // Sleep 57 seconds between bursts (3s/60s = ~5% active)
            },
        }
    }
}

/// Tracks network activity state to determine optimal power tier.
struct ActivityState {
    /// Timestamp (Unix seconds) of last data sent or received.
    last_data_activity_secs: i64,
    /// Timestamp of last peer discovery.
    last_peer_seen_secs: i64,
    /// Timestamp when High tier was entered.
    high_tier_entered_secs: i64,
    /// Current battery percentage (0-100). Updated by mobile layer.
    battery_percent: u8,
    /// Whether the user has manually enabled battery saver mode.
    user_battery_saver: bool,
    /// Number of currently connected peers.
    connected_peer_count: u32,
    /// Whether there are pending outbound messages awaiting delivery.
    has_pending_outbound: bool,
}

impl Default for ActivityState {
    fn default() -> Self {
        ActivityState {
            last_data_activity_secs: 0,
            last_peer_seen_secs: 0,
            high_tier_entered_secs: 0,
            battery_percent: 100,
            user_battery_saver: false,
            connected_peer_count: 0,
            has_pending_outbound: false,
        }
    }
}

/// Adaptive power manager for BLE mesh networking.
///
/// The mobile layer calls `evaluate()` periodically to get the recommended
/// power tier, then applies the corresponding scan/advertise parameters.
pub struct PowerManager {
    config: PowerConfig,
    state: Mutex<ActivityState>,
    current_tier: Mutex<PowerTier>,
}

impl PowerManager {
    /// Creates a new power manager with the given configuration.
    pub fn new(config: PowerConfig) -> Self {
        PowerManager {
            config,
            state: Mutex::new(ActivityState::default()),
            current_tier: Mutex::new(PowerTier::Balanced),
        }
    }

    /// Creates a power manager with default configuration.
    pub fn with_defaults() -> Self {
        Self::new(PowerConfig::default())
    }

    /// Returns a reference to the power configuration.
    pub fn config(&self) -> &PowerConfig {
        &self.config
    }

    /// Notifies that data was sent or received (promotes to High tier).
    pub fn on_data_activity(&self, now_secs: i64) {
        let mut state = self.state.lock().expect("power state lock");
        state.last_data_activity_secs = now_secs;
    }

    /// Notifies that a peer was discovered via BLE scan.
    pub fn on_peer_discovered(&self, now_secs: i64) {
        let mut state = self.state.lock().expect("power state lock");
        state.last_peer_seen_secs = now_secs;
    }

    /// Updates the battery percentage (called by mobile layer).
    pub fn update_battery(&self, percent: u8) {
        let mut state = self.state.lock().expect("power state lock");
        state.battery_percent = percent.min(100);
    }

    /// Sets whether the user has enabled battery saver mode.
    pub fn set_user_battery_saver(&self, enabled: bool) {
        let mut state = self.state.lock().expect("power state lock");
        state.user_battery_saver = enabled;
    }

    /// Updates the connected peer count.
    pub fn update_connected_peers(&self, count: u32) {
        let mut state = self.state.lock().expect("power state lock");
        state.connected_peer_count = count;
    }

    /// Updates whether there are pending outbound messages.
    pub fn set_has_pending_outbound(&self, has_pending: bool) {
        let mut state = self.state.lock().expect("power state lock");
        state.has_pending_outbound = has_pending;
    }

    /// Evaluates the current state and returns the recommended power tier.
    ///
    /// Call this periodically (e.g., every scan cycle) from the mobile layer.
    /// Apply the returned tier's parameters to the BLE scan/advertise settings.
    pub fn evaluate(&self, now_secs: i64) -> PowerTierRecommendation {
        let mut state = self.state.lock().expect("power state lock");
        let mut current = self.current_tier.lock().expect("tier lock");

        // ── Force UltraLow on critical battery or user request ──────
        if state.battery_percent <= self.config.critical_battery_percent || state.user_battery_saver
        {
            let new_tier = PowerTier::UltraLow;
            *current = new_tier;
            return self.build_recommendation(new_tier);
        }

        // ── Determine candidate tier from activity ──────────────────
        let secs_since_data = now_secs - state.last_data_activity_secs;
        let secs_since_peer = now_secs - state.last_peer_seen_secs;
        let secs_in_high = now_secs - state.high_tier_entered_secs;

        // For High tier duration check: only enforce limit if we're already in High
        let high_duration_ok = if *current == PowerTier::High {
            secs_in_high < self.config.high_duration_limit_secs as i64
        } else {
            true // Not yet in High, duration limit doesn't apply
        };

        let candidate = if state.has_pending_outbound && state.connected_peer_count > 0 {
            // Active data exchange — go high
            PowerTier::High
        } else if secs_since_data < self.config.high_inactivity_threshold_secs as i64
            && high_duration_ok
        {
            // Recent activity and haven't exceeded high duration limit
            PowerTier::High
        } else if state.connected_peer_count > 0
            || secs_since_peer < self.config.balanced_no_peers_threshold_secs as i64
        {
            // Peers present or recently seen
            PowerTier::Balanced
        } else {
            // No peers nearby — conserve battery
            PowerTier::LowPower
        };

        // ── Apply battery cap: disable High tier when battery is low ─
        let capped = if state.battery_percent <= self.config.low_battery_percent
            && candidate == PowerTier::High
        {
            PowerTier::Balanced
        } else {
            candidate
        };

        // ── Track High tier entry time ──────────────────────────────
        if capped == PowerTier::High && *current != PowerTier::High {
            state.high_tier_entered_secs = now_secs;
        }

        *current = capped;
        self.build_recommendation(capped)
    }

    /// Returns the current power tier without re-evaluating.
    pub fn current_tier(&self) -> PowerTier {
        *self.current_tier.lock().expect("tier lock")
    }

    fn build_recommendation(&self, tier: PowerTier) -> PowerTierRecommendation {
        PowerTierRecommendation {
            tier,
            scan_window_ms: self.config.scan_window_ms.get(tier),
            scan_interval_ms: self.config.scan_interval_ms.get(tier),
            advertise_interval_ms: self.config.advertise_interval_ms.get(tier),
            burst_scan_duration_ms: self.config.burst_scan_duration_ms.get(tier),
            burst_sleep_duration_ms: self.config.burst_sleep_duration_ms.get(tier),
            use_burst_mode: matches!(tier, PowerTier::LowPower | PowerTier::UltraLow),
        }
    }
}

/// Recommendation returned by `PowerManager::evaluate()`.
/// The mobile layer uses these values to configure BLE hardware.
#[derive(Debug, Clone)]
pub struct PowerTierRecommendation {
    pub tier: PowerTier,
    /// BLE scan window in milliseconds.
    pub scan_window_ms: u32,
    /// BLE scan interval in milliseconds.
    pub scan_interval_ms: u32,
    /// BLE advertising interval in milliseconds.
    pub advertise_interval_ms: u32,
    /// If `use_burst_mode` is true, scan for this duration then sleep.
    pub burst_scan_duration_ms: u32,
    /// Sleep duration between scan bursts.
    pub burst_sleep_duration_ms: u32,
    /// Whether to use burst mode (scan/sleep cycles) instead of continuous.
    pub use_burst_mode: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn now() -> i64 {
        chrono::Utc::now().timestamp()
    }

    #[test]
    fn test_defaults_to_balanced() {
        let pm = PowerManager::with_defaults();
        assert_eq!(pm.current_tier(), PowerTier::Balanced);
    }

    #[test]
    fn test_data_activity_promotes_to_high() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(1);

        let rec = pm.evaluate(t + 1);
        assert_eq!(rec.tier, PowerTier::High);
    }

    #[test]
    fn test_high_tier_auto_downgrades_after_timeout() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(1);

        // Enter High tier
        pm.evaluate(t + 1);

        // After high_duration_limit_secs + inactivity threshold, should downgrade
        let future = t
            + pm.config().high_duration_limit_secs as i64
            + pm.config().high_inactivity_threshold_secs as i64
            + 1;
        let rec = pm.evaluate(future);
        assert_eq!(rec.tier, PowerTier::Balanced); // Still peers, so Balanced
    }

    #[test]
    fn test_no_peers_downgrades_to_low_power() {
        let pm = PowerManager::with_defaults();
        let t = now();

        // No peer activity at all — far in the past
        let rec = pm.evaluate(t);
        assert_eq!(rec.tier, PowerTier::LowPower);
    }

    #[test]
    fn test_critical_battery_forces_ultra_low() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(5);
        pm.update_battery(10); // Critical

        let rec = pm.evaluate(t + 1);
        assert_eq!(rec.tier, PowerTier::UltraLow);
    }

    #[test]
    fn test_low_battery_caps_at_balanced() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(1);
        pm.update_battery(25); // Low but not critical

        let rec = pm.evaluate(t + 1);
        // Would normally be High due to activity, but capped at Balanced
        assert_eq!(rec.tier, PowerTier::Balanced);
    }

    #[test]
    fn test_user_battery_saver_forces_ultra_low() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(5);
        pm.update_battery(90);
        pm.set_user_battery_saver(true);

        let rec = pm.evaluate(t + 1);
        assert_eq!(rec.tier, PowerTier::UltraLow);
    }

    #[test]
    fn test_pending_outbound_with_peers_promotes_high() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_peer_discovered(t);
        pm.update_connected_peers(1);
        pm.set_has_pending_outbound(true);

        let rec = pm.evaluate(t + 1);
        assert_eq!(rec.tier, PowerTier::High);
    }

    #[test]
    fn test_burst_mode_in_low_power() {
        let pm = PowerManager::with_defaults();
        let t = now();
        let rec = pm.evaluate(t);
        assert_eq!(rec.tier, PowerTier::LowPower);
        assert!(rec.use_burst_mode);
        assert!(rec.burst_scan_duration_ms > 0);
        assert!(rec.burst_sleep_duration_ms > 0);
    }

    #[test]
    fn test_no_burst_mode_in_high() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_data_activity(t);
        pm.on_peer_discovered(t);
        pm.update_connected_peers(1);
        let rec = pm.evaluate(t + 1);
        assert_eq!(rec.tier, PowerTier::High);
        assert!(!rec.use_burst_mode);
    }

    #[test]
    fn test_tier_params_get() {
        let params = TierParams {
            high: 100u32,
            balanced: 200,
            low_power: 300,
            ultra_low: 400,
        };
        assert_eq!(params.get(PowerTier::High), 100);
        assert_eq!(params.get(PowerTier::Balanced), 200);
        assert_eq!(params.get(PowerTier::LowPower), 300);
        assert_eq!(params.get(PowerTier::UltraLow), 400);
    }

    #[test]
    fn test_peers_recently_seen_stays_balanced() {
        let pm = PowerManager::with_defaults();
        let t = now();
        pm.on_peer_discovered(t);

        // 30 seconds later, peer no longer connected but was recently seen
        let rec = pm.evaluate(t + 30);
        assert_eq!(rec.tier, PowerTier::Balanced);
    }
}
