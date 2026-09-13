package com.meijin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Bot {

    public static void main(String[] args) throws InterruptedException {
        // --- Variables d'environnement Discord ---
        // DISCORD_BOT_TOKEN (obligatoire) : le token du bot, généré dans le Discord Developer Portal.
        String token = System.getenv("DISCORD_BOT_TOKEN");
        if (token == null || token.isBlank() || token.equals("VOTRE_TOKEN_ICI")) {
            System.out.println("❌ La variable d'environnement DISCORD_BOT_TOKEN est manquante ou invalide.");
            System.out.println("   Définissez-la avant de lancer le bot, ex: export DISCORD_BOT_TOKEN=\"votre_token\"");
            return;
        }

        // DISCORD_GUILD_ID (optionnel) : ID d'un serveur de test.
        // Si défini, les commandes slash sont synchronisées instantanément sur CE serveur uniquement
        // (pratique en développement). Sinon, elles sont enregistrées globalement (peut prendre ~1h à apparaître).
        String guildIdEnv = System.getenv("DISCORD_GUILD_ID");
        Long guildId = null;
        if (guildIdEnv != null && !guildIdEnv.isBlank()) {
            try {
                guildId = Long.parseLong(guildIdEnv.trim());
            } catch (NumberFormatException e) {
                System.out.println("⚠️ DISCORD_GUILD_ID doit être un identifiant numérique valide. Enregistrement en mode global à la place.");
            }
        }

        JDA jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new CommandListener(guildId))
                .build();

        jda.awaitReady();
    }
}
