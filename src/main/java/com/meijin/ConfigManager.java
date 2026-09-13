package com.meijin;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gère la lecture et l'écriture de config.json.
 * La configuration contient PLUSIEURS profils, chacun identifié par un nom libre choisi par
 * l'admin (ex: "Événement A"), avec son propre salon, rôle, titre, texte, couleur, et une
 * liste de réactions gérée séparément via /config-reactions. Un membre choisit le profil
 * (donc le salon) via un menu dans /post-photo.
 */
public class ConfigManager {

    // Chemin du fichier de config, personnalisable via la variable d'environnement DISCORD_CONFIG_PATH
    // (utile par ex. pour pointer vers un volume persistant en déploiement).
    private static final Path CONFIG_FILE = Path.of(
            System.getenv().getOrDefault("DISCORD_CONFIG_PATH", "config.json")
    );

    public static final String DEFAULT_TITLE = "📸 Nouvelle publication";
    public static final String DEFAULT_TEXT_TEMPLATE = "Participant : {user}";
    public static final String DEFAULT_COLOR = "2f3136";

    /**
     * Charge la racine du fichier de config : { "profiles": { "<nom>": {...}, ... } }
     */
    public static JSONObject loadRoot() {
        JSONObject root = new JSONObject();
        root.put("profiles", new JSONObject());

        if (Files.exists(CONFIG_FILE)) {
            try {
                String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
                JSONObject saved = new JSONObject(content);
                if (saved.has("profiles")) {
                    root.put("profiles", saved.getJSONObject("profiles"));
                }
            } catch (Exception e) {
                System.out.println("⚠️ Erreur lors de la lecture de config.json: " + e.getMessage());
            }
        }
        return root;
    }

    public static void saveRoot(JSONObject root) {
        try {
            Files.writeString(CONFIG_FILE, root.toString(4), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("⚠️ Erreur lors de la sauvegarde de config.json: " + e.getMessage());
        }
    }

    public static JSONObject getProfiles(JSONObject root) {
        return root.getJSONObject("profiles");
    }

    /**
     * Retourne le profil existant portant ce nom, ou en crée un nouveau (avec les valeurs par
     * défaut) et l'enregistre immédiatement dans la map "profiles" fournie.
     */
    public static JSONObject getOrCreateProfile(JSONObject profiles, String name) {
        if (profiles.has(name)) {
            return profiles.getJSONObject(name);
        }
        JSONObject profile = new JSONObject();
        profile.put("target_channel_id", JSONObject.NULL);
        profile.put("ping_role_id", JSONObject.NULL);
        profile.put("title", DEFAULT_TITLE);
        profile.put("text_template", DEFAULT_TEXT_TEMPLATE);
        profile.put("color", DEFAULT_COLOR);
        profile.put("reactions", new org.json.JSONArray());
        profiles.put(name, profile);
        return profile;
    }

    public static Long getLongOrNull(JSONObject obj, String key) {
        return obj.isNull(key) ? null : obj.getLong(key);
    }
}


