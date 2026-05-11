package aps.shared.model;

/**
 * Enumeração para definir os papéis/permissões dos usuários
 */
public enum UserRole {
    ADMIN("Admin", "Acesso total a todos os canais e controle de permissões"),
    MEMBER("Membro", "Acesso limitado aos canais autorizados");

    private final String displayName;
    private final String description;

    UserRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Verifica se o role tem permissão total
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}
