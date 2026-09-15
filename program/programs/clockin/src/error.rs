use anchor_lang::prelude::*;

#[error_code]
pub enum ClockInError {
    #[msg("Position inactive : dépose une mise avant d'utiliser cette fonction")]
    PositionInactive,
    #[msg("Solde effectif sous le minimum requis")]
    InsufficientStake,
    #[msg("Le jour fourni ne correspond pas au jour UTC courant")]
    DayMismatch,
    #[msg("Une demande de sortie est déjà en cours")]
    ExitAlreadyRequested,
    #[msg("Aucune demande de sortie en cours")]
    NoExitRequested,
    #[msg("La sortie n'est pas encore débloquée")]
    ExitLocked,
    #[msg("La sortie est débloquée : cette action n'est plus possible")]
    ExitUnlocked,
    #[msg("Dépôt impossible pendant une demande de sortie")]
    StakeDuringExit,
    #[msg("Aucun jour à régler sur ce profil")]
    NothingToReap,
    #[msg("Montant invalide")]
    InvalidAmount,
    #[msg("Faucet désactivé sur ce déploiement")]
    FaucetDisabled,
    #[msg("Faucet déjà utilisé par ce profil")]
    FaucetAlreadyClaimed,
    #[msg("Compte de tokens destinataire invalide")]
    InvalidOwnerTokenAccount,
    #[msg("Paramètre de configuration invalide")]
    InvalidConfigParam,
}
