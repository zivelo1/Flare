//! Encrypted local storage using SQLCipher.
//!
//! All Flare data is stored in a SQLCipher-encrypted SQLite database.
//! The encryption key is derived from the user's passphrase using Argon2id.

pub mod database;

pub use database::FlareDatabase;
