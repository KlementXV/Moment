use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error(pub StatusCode, pub &'static str);

impl Error {
    pub fn bad(message: &'static str) -> Self {
        Self(StatusCode::BAD_REQUEST, message)
    }
    pub fn auth() -> Self {
        Self(
            StatusCode::UNAUTHORIZED,
            "Connexion expirée ou invalide. Reconnectez votre wallet.",
        )
    }
    pub fn forbidden() -> Self {
        Self(
            StatusCode::FORBIDDEN,
            "Publiez aujourd’hui avec une mise suffisante pour accéder aux Moments.",
        )
    }
    pub fn conflict() -> Self {
        Self(
            StatusCode::CONFLICT,
            "Un autre Moment existe déjà pour ce jour. Reprenez la publication initiale.",
        )
    }
    pub fn unavailable() -> Self {
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporairement indisponible. Réessayez plus tard.",
        )
    }
    pub fn internal() -> Self {
        Self(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Une erreur interne est survenue. Réessayez plus tard.",
        )
    }
    pub fn missing() -> Self {
        Self(
            StatusCode::NOT_FOUND,
            "Moment introuvable. Actualisez le feed.",
        )
    }
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.1)
    }
}
impl std::error::Error for Error {}
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error": self.1}))).into_response()
    }
}
