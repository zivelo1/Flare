//! SQLCipher encrypted database for Flare.
//!
//! Stores identities, contacts, messages, and routing state.
//! All data encrypted at rest using a key derived from the user's passphrase.

use argon2::{password_hash::SaltString, Argon2, PasswordHasher};
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

            CREATE TABLE IF NOT EXISTS groups (
                group_id TEXT PRIMARY KEY,
                group_name TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                creator_device_id TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                joined_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (group_id, device_id),
                FOREIGN KEY (group_id) REFERENCES groups(group_id)
            );

            CREATE TABLE IF NOT EXISTS duress_config (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                passphrase_hash BLOB NOT NULL,
                salt BLOB NOT NULL
            );

            CREATE TABLE IF NOT EXISTS rendezvous_tokens (
                token BLOB PRIMARY KEY,
                source_type TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                expires_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS active_searches (
                token BLOB PRIMARY KEY,
                ephemeral_private_key BLOB NOT NULL,
                source_type TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS phone_registry (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                phone_number_hash BLOB NOT NULL
            );
            ",
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
    #[allow(clippy::type_complexity)]
    pub fn load_identity(&self) -> Result<Option<(Vec<u8>, Vec<u8>)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT signing_key_bytes, agreement_key_bytes FROM local_identity WHERE id = 1",
        )?;

        let result = stmt.query_row([], |row| {
            Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
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
    pub fn load_contact(
        &self,
        device_id: &DeviceId,
    ) -> Result<Option<PublicIdentity>, DatabaseError> {
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
    pub fn list_contacts(
        &self,
    ) -> Result<Vec<(PublicIdentity, Option<String>, bool)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT device_id, signing_public_key, agreement_public_key, display_name, is_verified
             FROM contacts ORDER BY last_seen DESC",
        )?;

        let contacts = stmt
            .query_map([], |row| {
                let device_id_hex: String = row.get(0)?;
                let signing_bytes: Vec<u8> = row.get(1)?;
                let agreement_bytes: Vec<u8> = row.get(2)?;
                let display_name: Option<String> = row.get(3)?;
                let is_verified: bool = row.get(4)?;

                let mut signing_key = [0u8; 32];
                let mut agreement_key = [0u8; 32];
                signing_key.copy_from_slice(&signing_bytes);
                agreement_key.copy_from_slice(&agreement_bytes);

                let device_id = DeviceId::from_hex(&device_id_hex).unwrap_or(DeviceId([0; 16]));

                Ok((
                    PublicIdentity {
                        signing_public_key: signing_key,
                        agreement_public_key: agreement_key,
                        device_id,
                    },
                    display_name,
                    is_verified,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(contacts)
    }

    /// Stores a message in the local database.
    #[allow(clippy::too_many_arguments)]
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
    #[allow(clippy::type_complexity)]
    pub fn get_messages_for_conversation(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<(String, String, String, u8, Vec<u8>, String, bool, u8)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT message_id, conversation_id, sender_device_id, content_type,
                    plaintext, created_at, is_outgoing, delivery_status
             FROM messages
             WHERE conversation_id = ?1
             ORDER BY created_at ASC",
        )?;

        let messages = stmt
            .query_map(params![conversation_id], |row| {
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
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(messages)
    }

    // ── Groups ──────────────────────────────────────────────────────

    /// Creates a new group and returns the group ID.
    pub fn create_group(
        &self,
        group_id: &str,
        group_name: &str,
        creator_device_id: &str,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT INTO groups (group_id, group_name, creator_device_id) VALUES (?1, ?2, ?3)",
            params![group_id, group_name, creator_device_id],
        )?;
        Ok(())
    }

    /// Adds a member to a group.
    pub fn add_group_member(&self, group_id: &str, device_id: &str) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR IGNORE INTO group_members (group_id, device_id) VALUES (?1, ?2)",
            params![group_id, device_id],
        )?;
        Ok(())
    }

    /// Removes a member from a group.
    pub fn remove_group_member(
        &self,
        group_id: &str,
        device_id: &str,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "DELETE FROM group_members WHERE group_id = ?1 AND device_id = ?2",
            params![group_id, device_id],
        )?;
        Ok(())
    }

    /// Lists all groups.
    pub fn list_groups(&self) -> Result<Vec<(String, String, String, String)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT group_id, group_name, created_at, creator_device_id FROM groups ORDER BY created_at DESC"
        )?;

        let groups = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(groups)
    }

    /// Gets all member device IDs for a group.
    pub fn get_group_members(&self, group_id: &str) -> Result<Vec<String>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT device_id FROM group_members WHERE group_id = ?1 ORDER BY joined_at ASC",
        )?;

        let members = stmt
            .query_map(params![group_id], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(members)
    }

    // ── Delivery Status ────────────────────────────────────────────

    /// Updates the delivery status of a stored message.
    pub fn update_delivery_status(
        &self,
        message_id: &str,
        status: u8,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "UPDATE messages SET delivery_status = ?2 WHERE message_id = ?1",
            params![message_id, status],
        )?;
        Ok(())
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
            params![
                message_id,
                recipient_device_id,
                encrypted_payload,
                ttl_seconds
            ],
        )?;
        Ok(())
    }

    /// Gets all pending outbound messages.
    pub fn get_pending_outbound(&self) -> Result<Vec<(String, String, Vec<u8>)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT message_id, recipient_device_id, encrypted_payload
             FROM pending_outbox ORDER BY created_at ASC",
        )?;

        let messages = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Vec<u8>>(2)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(messages)
    }

    // ── Duress PIN ──────────────────────────────────────────────────

    /// Sets (or replaces) the duress passphrase.
    /// Stores an Argon2id hash so we can check it on next login.
    pub fn set_duress_passphrase(&self, passphrase: &str) -> Result<(), DatabaseError> {
        let salt = SaltString::generate(&mut OsRng);
        let argon2 = Argon2::default();
        let hash = argon2
            .hash_password(passphrase.as_bytes(), &salt)
            .map_err(|e| DatabaseError::KeyDerivation(e.to_string()))?;

        let hash_string = hash.to_string();
        self.conn.execute(
            "INSERT OR REPLACE INTO duress_config (id, passphrase_hash, salt) VALUES (1, ?1, ?2)",
            params![hash_string.as_bytes(), salt.as_str().as_bytes()],
        )?;
        Ok(())
    }

    /// Checks if a passphrase matches the stored duress passphrase.
    /// Returns false if no duress passphrase has been configured.
    pub fn check_duress_passphrase(&self, passphrase: &str) -> Result<bool, DatabaseError> {
        let result: Result<Vec<u8>, _> = self.conn.query_row(
            "SELECT passphrase_hash FROM duress_config WHERE id = 1",
            [],
            |row| row.get(0),
        );

        match result {
            Ok(hash_bytes) => {
                let hash_str = String::from_utf8(hash_bytes)
                    .map_err(|e| DatabaseError::Serialization(e.to_string()))?;

                use argon2::PasswordVerifier;
                let parsed_hash = argon2::PasswordHash::new(&hash_str)
                    .map_err(|e| DatabaseError::KeyDerivation(e.to_string()))?;

                Ok(Argon2::default()
                    .verify_password(passphrase.as_bytes(), &parsed_hash)
                    .is_ok())
            }
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(false),
            Err(e) => Err(DatabaseError::Sqlite(e)),
        }
    }

    /// Returns true if a duress passphrase has been configured.
    pub fn has_duress_passphrase(&self) -> Result<bool, DatabaseError> {
        let count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM duress_config", [], |row| row.get(0))?;
        Ok(count > 0)
    }

    /// Removes the duress passphrase configuration.
    pub fn clear_duress_passphrase(&self) -> Result<(), DatabaseError> {
        self.conn.execute("DELETE FROM duress_config", [])?;
        Ok(())
    }

    // ── Rendezvous Discovery ─────────────────────────────────────

    /// Stores a rendezvous token for responding to incoming searches.
    pub fn store_rendezvous_token(
        &self,
        token: &[u8],
        source_type: &str,
        expires_at: &str,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR REPLACE INTO rendezvous_tokens (token, source_type, expires_at) VALUES (?1, ?2, ?3)",
            params![token, source_type, expires_at],
        )?;
        Ok(())
    }

    /// Removes a rendezvous token.
    pub fn remove_rendezvous_token(&self, token: &[u8]) -> Result<(), DatabaseError> {
        self.conn.execute(
            "DELETE FROM rendezvous_tokens WHERE token = ?1",
            params![token],
        )?;
        Ok(())
    }

    /// Lists all rendezvous tokens.
    pub fn list_rendezvous_tokens(&self) -> Result<Vec<(Vec<u8>, String, String)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT token, source_type, expires_at FROM rendezvous_tokens ORDER BY created_at ASC",
        )?;
        let tokens = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(tokens)
    }

    /// Stores an active search (outbound rendezvous query).
    pub fn store_active_search(
        &self,
        token: &[u8],
        ephemeral_private_key: &[u8],
        source_type: &str,
    ) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR REPLACE INTO active_searches (token, ephemeral_private_key, source_type) VALUES (?1, ?2, ?3)",
            params![token, ephemeral_private_key, source_type],
        )?;
        Ok(())
    }

    /// Removes an active search.
    pub fn remove_active_search(&self, token: &[u8]) -> Result<(), DatabaseError> {
        self.conn.execute(
            "DELETE FROM active_searches WHERE token = ?1",
            params![token],
        )?;
        Ok(())
    }

    /// Loads an active search by token.
    pub fn load_active_search(
        &self,
        token: &[u8],
    ) -> Result<Option<(Vec<u8>, String)>, DatabaseError> {
        let mut stmt = self.conn.prepare(
            "SELECT ephemeral_private_key, source_type FROM active_searches WHERE token = ?1",
        )?;
        let result = stmt.query_row(params![token], |row| {
            Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, String>(1)?))
        });
        match result {
            Ok(data) => Ok(Some(data)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(DatabaseError::Sqlite(e)),
        }
    }

    /// Prunes expired rendezvous tokens.
    pub fn prune_expired_rendezvous(&self) -> Result<usize, DatabaseError> {
        let count = self.conn.execute(
            "DELETE FROM rendezvous_tokens WHERE expires_at < datetime('now')",
            [],
        )?;
        Ok(count)
    }

    /// Stores the user's phone number hash for rendezvous (optional registration).
    pub fn store_phone_hash(&self, phone_hash: &[u8]) -> Result<(), DatabaseError> {
        self.conn.execute(
            "INSERT OR REPLACE INTO phone_registry (id, phone_number_hash) VALUES (1, ?1)",
            params![phone_hash],
        )?;
        Ok(())
    }

    /// Loads the stored phone number hash.
    pub fn load_phone_hash(&self) -> Result<Option<Vec<u8>>, DatabaseError> {
        let result = self.conn.query_row(
            "SELECT phone_number_hash FROM phone_registry WHERE id = 1",
            [],
            |row| row.get::<_, Vec<u8>>(0),
        );
        match result {
            Ok(hash) => Ok(Some(hash)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(DatabaseError::Sqlite(e)),
        }
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
        )
        .unwrap();

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
        assert_eq!(
            loaded.unwrap().signing_public_key,
            public.signing_public_key
        );
    }

    #[test]
    fn test_list_contacts() {
        let db = test_db();

        for name in &["Alice", "Bob", "Carol"] {
            let peer = Identity::generate();
            db.upsert_contact(&peer.public_identity(), Some(name), false)
                .unwrap();
        }

        let contacts = db.list_contacts().unwrap();
        assert_eq!(contacts.len(), 3);
    }

    #[test]
    fn test_store_and_get_messages() {
        let db = test_db();
        let peer = Identity::generate();
        db.upsert_contact(&peer.public_identity(), Some("Alice"), false)
            .unwrap();

        let conv_id = peer.device_id().to_hex();

        // Insert a conversation row (required by FK in some setups)
        db.conn
            .execute(
                "INSERT INTO conversations (id, peer_device_id) VALUES (?1, ?2)",
                params![conv_id, conv_id],
            )
            .unwrap();

        db.store_message(
            "msg-001",
            &conv_id,
            &conv_id,
            1,
            b"Hello",
            "2025-01-01T00:00:00Z",
            false,
        )
        .unwrap();
        db.store_message(
            "msg-002",
            &conv_id,
            "self",
            1,
            b"Hi back",
            "2025-01-01T00:00:01Z",
            true,
        )
        .unwrap();

        let msgs = db.get_messages_for_conversation(&conv_id).unwrap();
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0].0, "msg-001");
        assert_eq!(msgs[1].0, "msg-002");
    }

    #[test]
    fn test_delivery_status_update() {
        let db = test_db();
        let peer = Identity::generate();
        db.upsert_contact(&peer.public_identity(), Some("Bob"), false)
            .unwrap();

        let conv_id = peer.device_id().to_hex();
        db.conn
            .execute(
                "INSERT INTO conversations (id, peer_device_id) VALUES (?1, ?2)",
                params![conv_id, conv_id],
            )
            .unwrap();

        db.store_message(
            "msg-100",
            &conv_id,
            "self",
            1,
            b"Test",
            "2025-01-01T00:00:00Z",
            true,
        )
        .unwrap();
        db.update_delivery_status("msg-100", 2).unwrap();

        let msgs = db.get_messages_for_conversation(&conv_id).unwrap();
        assert_eq!(msgs[0].7, 2); // delivery_status
    }

    #[test]
    fn test_group_operations() {
        let db = test_db();

        db.create_group("grp-001", "Test Group", "device-aaa")
            .unwrap();
        db.add_group_member("grp-001", "device-aaa").unwrap();
        db.add_group_member("grp-001", "device-bbb").unwrap();
        db.add_group_member("grp-001", "device-ccc").unwrap();

        let groups = db.list_groups().unwrap();
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].1, "Test Group");

        let members = db.get_group_members("grp-001").unwrap();
        assert_eq!(members.len(), 3);

        db.remove_group_member("grp-001", "device-bbb").unwrap();
        let members = db.get_group_members("grp-001").unwrap();
        assert_eq!(members.len(), 2);
    }

    #[test]
    fn test_duress_passphrase() {
        let db = test_db();

        // No duress passphrase configured initially
        assert!(!db.has_duress_passphrase().unwrap());
        assert!(!db.check_duress_passphrase("anything").unwrap());

        // Set duress passphrase
        db.set_duress_passphrase("panic123").unwrap();
        assert!(db.has_duress_passphrase().unwrap());

        // Correct duress passphrase matches
        assert!(db.check_duress_passphrase("panic123").unwrap());

        // Wrong passphrase does not match
        assert!(!db.check_duress_passphrase("wrong-pass").unwrap());
        assert!(!db.check_duress_passphrase("").unwrap());

        // Clear and verify
        db.clear_duress_passphrase().unwrap();
        assert!(!db.has_duress_passphrase().unwrap());
        assert!(!db.check_duress_passphrase("panic123").unwrap());
    }

    #[test]
    fn test_outbox_operations() {
        let db = test_db();

        db.queue_outbound("msg-001", "device-abc", b"encrypted-data", 86400)
            .unwrap();
        db.queue_outbound("msg-002", "device-def", b"more-data", 86400)
            .unwrap();

        let pending = db.get_pending_outbound().unwrap();
        assert_eq!(pending.len(), 2);

        db.remove_from_outbox("msg-001").unwrap();
        let pending = db.get_pending_outbound().unwrap();
        assert_eq!(pending.len(), 1);
    }
}
