//! SQLCipher encrypted database for Flare.
//!
//! Stores identities, contacts, messages, and routing state.
//! All data encrypted at rest using a key derived from the user's passphrase.

use argon2::{Argon2, PasswordHasher, password_hash::SaltString};
use rand_core::OsRng;
use rusqlite::{params, Connection};

use crate::crypto::identity::{DeviceId, PublicIdentity};

/// Errors from database operations.
#[derive(Debug, thiserror::Error)]
pub enum DatabaseError {
    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    #[error("Key derivation error: {0}")]
    KeyDerivation(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Database not initialized")]
    NotInitialized,
}

/// Encrypted database handle for Flare.
pub struct FlareDatabase {
    conn: Connection,
}

impl FlareDatabase {
    /// Opens (or creates) an encrypted database at the given path.
    ///
    /// The passphrase is used to derive the encryption key via Argon2id.
    /// On first open, the database schema is created automatically.
    pub fn open(path: &str, passphrase: &str) -> Result<Self, DatabaseError> {
        let conn = Connection::open(path)?;

        // Derive encryption key from passphrase
        let key_hex = Self::derive_key(passphrase)?;
        conn.pragma_update(None, "key", &key_hex)?;

        // Performance pragmas
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;

        let db = FlareDatabase { conn };
        db.create_schema()?;
        Ok(db)
    }

    /// Opens an in-memory encrypted database (for testing).
    pub fn open_in_memory(passphrase: &str) -> Result<Self, DatabaseError> {
        let conn = Connection::open_in_memory()?;
        let key_hex = Self::derive_key(passphrase)?;
        conn.pragma_update(None, "key", &key_hex)?;

        let db = FlareDatabase { conn };
        db.create_schema()?;
        Ok(db)
    }

    /// Derives a 256-bit encryption key from a passphrase using Argon2id.
    fn derive_key(passphrase: &str) -> Result<String, DatabaseError> {
        let salt = SaltString::generate(&mut OsRng);
        let argon2 = Argon2::default();
        let hash = argon2
            .hash_password(passphrase.as_bytes(), &salt)
            .map_err(|e| DatabaseError::KeyDerivation(e.to_string()))?;
        // Use the hash output as hex key for SQLCipher
        Ok(format!("x'{}'", hex_encode(hash.hash.unwrap().as_bytes())))
    }

    /// Creates the database schema if it doesn't exist.
    fn create_schema(&self) -> Result<(), DatabaseError> {
        self.conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS local_identity (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                signing_key_bytes BLOB NOT NULL,
                agreement_key_bytes BLOB NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS contacts (
                device_id TEXT PRIMARY KEY,
                signing_public_key BLOB NOT NULL,
                agreement_public_key BLOB NOT NULL,
                display_name TEXT,
                trust_level INTEGER NOT NULL DEFAULT 0,
                first_seen TEXT NOT NULL DEFAULT (datetime('now')),
                last_seen TEXT NOT NULL DEFAULT (datetime('now')),
                is_verified INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                peer_device_id TEXT NOT NULL,
                last_message_at TEXT,
                unread_count INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (peer_device_id) REFERENCES contacts(device_id)
            );

            CREATE TABLE IF NOT EXISTS messages (
                message_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                sender_device_id TEXT NOT NULL,
                content_type INTEGER NOT NULL,
                plaintext BLOB,
                created_at TEXT NOT NULL,
                received_at TEXT NOT NULL DEFAULT (datetime('now')),
                is_outgoing INTEGER NOT NULL,
                delivery_status INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            );

            CREATE INDEX IF NOT EXISTS idx_messages_conversation
                ON messages(conversation_id, created_at);

            CREATE TABLE IF NOT EXISTS pending_outbox (
                message_id TEXT PRIMARY KEY,
                recipient_device_id TEXT NOT NULL,
                encrypted_payload BLOB NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                ttl_seconds INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0
            );
            "
        )?;
        Ok(())
    }

    /// Stores the local device identity (called once on first launch).
    pub fn store_identity(
        &self,
        signing_key_bytes: &[u8],
        agreement_key_bytes: &[u8],
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR REPLACE INTO local_identity (id, signing_key_bytes, agreement_key_bytes)
             VALUES (1, ?1, ?2)",
            params![signing_key_bytes, agreement_key_bytes],
        )?;
        Ok(())
    }

    /// Loads the local device identity.
    pub fn load_identity(&self) -> Result<Option<(Vec<u8>, Vec<u8>)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT signing_key_bytes, agreement_key_bytes FROM local_identity WHERE id = 1"
        )?;

        let result = stmt.query_row([], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, Vec<u8>>(1)?,
            ))
        });

        match result {
            Ok(keys) => Ok(Some(keys)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(DatabaseError::Sqlite(e)),
        }
    }

    /// Adds or updates a contact.
    pub fn upsert_contact(
        &self,
        public_identity: &PublicIdentity,
        display_name: Option<&str>,
        is_verified: bool,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT INTO contacts (device_id, signing_public_key, agreement_public_key, display_name, is_verified, last_seen)
             VALUES (?1, ?2, ?3, ?4, ?5, datetime('now'))
             ON CONFLICT(device_id) DO UPDATE SET
                display_name = COALESCE(?4, display_name),
                is_verified = MAX(is_verified, ?5),
                last_seen = datetime('now')",
            params![
                public_identity.device_id.to_hex(),
                public_identity.signing_public_key.as_slice(),
                public_identity.agreement_public_key.as_slice(),
                display_name,
                is_verified as i32,
            ],
        )?;
        Ok(())
    }

    /// Loads a contact by device ID.
    pub fn load_contact(&self, device_id: &DeviceId) -> Result<Option<PublicIdentity>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT signing_public_key, agreement_public_key, device_id FROM contacts WHERE device_id = ?1"
        )?;

        let result = stmt.query_row(params![device_id.to_hex()], |row| {
            let signing_bytes: Vec<u8> = row.get(0)?;
            let agreement_bytes: Vec<u8> = row.get(1)?;

            let mut signing_key = [0u8; 32];
            let mut agreement_key = [0u8; 32];
            signing_key.copy_from_slice(&signing_bytes);
            agreement_key.copy_from_slice(&agreement_bytes);

            Ok(PublicIdentity {
                signing_public_key: signing_key,
                agreement_public_key: agreement_key,
                device_id: device_id.clone(),
            })
        });

        match result {
            Ok(identity) => Ok(Some(identity)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(DatabaseError::Sqlite(e)),
        }
    }

    /// Lists all contacts.
    pub fn list_contacts(&self) -> Result<Vec<(PublicIdentity, Option<String>, bool)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT device_id, signing_public_key, agreement_public_key, display_name, is_verified
             FROM contacts ORDER BY last_seen DESC"
        )?;

        let contacts = stmt.query_map([], |row| {
            let device_id_hex: String = row.get(0)?;
            let signing_bytes: Vec<u8> = row.get(1)?;
            let agreement_bytes: Vec<u8> = row.get(2)?;
            let display_name: Option<String> = row.get(3)?;
            let is_verified: bool = row.get(4)?;

            let mut signing_key = [0u8; 32];
            let mut agreement_key = [0u8; 32];
            signing_key.copy_from_slice(&signing_bytes);
            agreement_key.copy_from_slice(&agreement_bytes);

            let device_id = DeviceId::from_hex(&device_id_hex)
                .unwrap_or(DeviceId([0; 16]));

            Ok((
                PublicIdentity {
                    signing_public_key: signing_key,
                    agreement_public_key: agreement_key,
                    device_id,
                },
                display_name,
                is_verified,
            ))
        })?.collect::<Result<Vec<_>, _>>()?;

        Ok(contacts)
    }

    /// Stores a message in the local database.
    pub fn store_message(
        &self,
        message_id: &str,
        conversation_id: &str,
        sender_device_id: &str,
        content_type: u8,
        plaintext: &[u8],
        created_at: &str,
        is_outgoing: bool,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR IGNORE INTO messages
             (message_id, conversation_id, sender_device_id, content_type, plaintext, created_at, is_outgoing)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                message_id,
                conversation_id,
                sender_device_id,
                content_type as i32,
                plaintext,
                created_at,
                is_outgoing as i32,
            ],
        )?;
        Ok(())
    }

    /// Gets all messages for a conversation, ordered by creation time.
    pub fn get_messages_for_conversation(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<(String, String, String, u8, Vec<u8>, String, bool, u8)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT message_id, conversation_id, sender_device_id, content_type,
                    plaintext, created_at, is_outgoing, delivery_status
             FROM messages
             WHERE conversation_id = ?1
             ORDER BY created_at ASC"
        )?;

        let messages = stmt.query_map(params![conversation_id], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, u8>(3)?,
                row.get::<_, Vec<u8>>(4)?,
                row.get::<_, String>(5)?,
                row.get::<_, bool>(6)?,
                row.get::<_, u8>(7)?,
            ))
        })?.collect::<Result<Vec<_>, _>>()?;

        Ok(messages)
    }

    /// Queues a message for outbound delivery.
    pub fn queue_outbound(
        &self,
        message_id: &str,
        recipient_device_id: &str,
        encrypted_payload: &[u8],
        ttl_seconds: u32,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR REPLACE INTO pending_outbox
             (message_id, recipient_device_id, encrypted_payload, ttl_seconds)
             VALUES (?1, ?2, ?3, ?4)",
            params![message_id, recipient_device_id, encrypted_payload, ttl_seconds],
        )?;
        Ok(())
    }

    /// Gets all pending outbound messages.
    pub fn get_pending_outbound(&self) -> Result<Vec<(String, String, Vec<u8>)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT message_id, recipient_device_id, encrypted_payload
             FROM pending_outbox ORDER BY created_at ASC"
        )?;

        let messages = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, Vec<u8>>(2)?,
            ))
        })?.collect::<Result<Vec<_>, _>>()?;

        Ok(messages)
    }

    /// Removes a message from the outbox after successful delivery.
    pub fn remove_from_outbox(&self, message_id: &str) -> Result<(), DatabaseError> {
        self.conn.execute(
            "DELETE FROM pending_outbox WHERE message_id = ?1",
            params![message_id],
        )?;
        Ok(())
    }
}

/// Simple hex encoding helper (avoids pulling in the `hex` crate for one function).
fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;

    fn test_db() -> FlareDatabase {
        FlareDatabase::open_in_memory("test-passphrase-123").unwrap()
    }

    #[test]
    fn test_schema_creation() {
        let _db = test_db(); // Should not panic
    }

    #[test]
    fn test_store_and_load_identity() {
        let db = test_db();
        let identity = Identity::generate();

        db.store_identity(
            identity.signing_key_bytes(),
            &identity.agreement_key_bytes(),
        ).unwrap();

        let loaded = db.load_identity().unwrap();
        assert!(loaded.is_some());

        let (signing, agreement) = loaded.unwrap();
        let restored = Identity::from_key_bytes(&signing, &agreement).unwrap();
        assert_eq!(identity.device_id(), restored.device_id());
    }

    #[test]
    fn test_load_identity_when_empty() {
        let db = test_db();
        let loaded = db.load_identity().unwrap();
        assert!(loaded.is_none());
    }

    #[test]
    fn test_upsert_and_load_contact() {
        let db = test_db();
        let peer = Identity::generate();
        let public = peer.public_identity();

        db.upsert_contact(&public, Some("Alice"), true).unwrap();

        let loaded = db.load_contact(&public.device_id).unwrap();
        assert!(loaded.is_some());
        assert_eq!(loaded.unwrap().signing_public_key, public.signing_public_key);
    }

    #[test]
    fn test_list_contacts() {
        let db = test_db();

        for name in &["Alice", "Bob", "Carol"] {
            let peer = Identity::generate();
            db.upsert_contact(&peer.public_identity(), Some(name), false).unwrap();
        }

        let contacts = db.list_contacts().unwrap();
        assert_eq!(contacts.len(), 3);
    }

    #[test]
    fn test_outbox_operations() {
        let db = test_db();

        db.queue_outbound("msg-001", "device-abc", b"encrypted-data", 86400).unwrap();
        db.queue_outbound("msg-002", "device-def", b"more-data", 86400).unwrap();

        let pending = db.get_pending_outbound().unwrap();
        assert_eq!(pending.len(), 2);

        db.remove_from_outbox("msg-001").unwrap();
        let pending = db.get_pending_outbound().unwrap();
        assert_eq!(pending.len(), 1);
    }
}
