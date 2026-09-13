package com.meijin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.ready.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class CommandListener extends ListenerAdapter {

    // Valide soit un émoji standard (unicode), soit un émoji custom du serveur au format <:nom:id> / <a:nom:id>.
    private static final Pattern UNICODE_EMOJI_PATTERN = Pattern.compile(
            "^[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]+$"
    );
    private static final Pattern CUSTOM_EMOJI_PATTERN = Pattern.compile("^<a?:\\w{2,32}:\\d{17,20}>$");

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^[0-9a-fA-F]{6}$");
    private static final String SELECT_PREFIX = "postphoto:";
    private static final long PENDING_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_REACTIONS_PER_PROFILE = 20; // limite Discord sur le nombre de réactions par message

    // ID du serveur de test (variable d'environnement DISCORD_GUILD_ID), ou null pour un enregistrement global.
    private final Long guildId;

    // Images en attente qu'un membre choisisse le salon (menu de /post-photo).
    private final Map<String, PendingPost> pendingPosts = new ConcurrentHashMap<>();

    private record PendingPost(long requesterId, List<String> imageUrls, long createdAt) {
    }

    public CommandListener(Long guildId) {
        this.guildId = guildId;
    }

    @Override
    public void onReady(ReadyEvent event) {
        System.out.println("✅ Bot connecté en tant que " + event.getJDA().getSelfUser().getName());

        DefaultMemberPermissions adminOnly = DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR);

        SlashCommandData configCommand = Commands.slash("config", "Créer ou modifier une configuration (un profil = un salon)")
                .addOption(OptionType.STRING, "nom", "Nom de la configuration à créer ou modifier (ex: \"Événement A\")", true, true)
                .addOption(OptionType.CHANNEL, "salon", "Salon où les photos seront publiées (obligatoire à la création)", false)
                .addOption(OptionType.ROLE, "role", "Rôle à ping lors d'une publication (laisser vide pour désactiver)", false)
                .addOption(OptionType.STRING, "titre", "Titre affiché en haut du message (les émojis, standards ou custom du serveur, y sont acceptés)", false)
                .addOption(OptionType.STRING, "texte", "Texte du corps du message. Utilisez {user} pour mentionner le participant", false)
                .addOption(OptionType.STRING, "couleur", "Couleur d'accent en hexadécimal, ex: 2f3136 ou #ff0000", false)
                .setDefaultPermissions(adminOnly);

        SlashCommandData configListeCommand = Commands.slash("config-liste", "Lister toutes les configurations existantes")
                .setDefaultPermissions(adminOnly);

        SlashCommandData configSupprimerCommand = Commands.slash("config-supprimer", "Supprimer une configuration existante")
                .addOption(OptionType.STRING, "nom", "Nom de la configuration à supprimer", true, true)
                .setDefaultPermissions(adminOnly);

        OptionData reactionActionOption = new OptionData(OptionType.STRING, "action", "Action à effectuer sur les réactions", true)
                .addChoice("Ajouter", "ajouter")
                .addChoice("Supprimer", "supprimer")
                .addChoice("Vider", "vider")
                .addChoice("Lister", "lister");

        SlashCommandData configReactionsCommand = Commands.slash("config-reactions", "Gérer les réactions d'une configuration (une par sujet)")
                .addOption(OptionType.STRING, "nom", "Nom de la configuration à modifier", true, true)
                .addOptions(reactionActionOption)
                .addOption(OptionType.STRING, "emoji", "Émoji concerné (standard ou custom du serveur) — requis pour ajouter/supprimer", false)
                .setDefaultPermissions(adminOnly);

        SlashCommandData postPhotoCommand = Commands.slash("post-photo", "Envoyer une ou deux photos dans un salon (au choix)")
                .addOption(OptionType.ATTACHMENT, "image", "Première image à publier", true)
                .addOption(OptionType.ATTACHMENT, "image2", "Deuxième image à publier (optionnel)", false);

        if (guildId != null) {
            Guild guild = event.getJDA().getGuildById(guildId);
            if (guild == null) {
                System.out.println("⚠️ DISCORD_GUILD_ID (" + guildId + ") introuvable : le bot n'est pas sur ce serveur. Enregistrement en mode global à la place.");
                registerGlobally(event, configCommand, configListeCommand, configSupprimerCommand, configReactionsCommand, postPhotoCommand);
                return;
            }
            guild.updateCommands().addCommands(configCommand, configListeCommand, configSupprimerCommand, configReactionsCommand, postPhotoCommand).queue(
                    cmds -> System.out.println("🔄 " + cmds.size() + " commande(s) slash synchronisée(s) sur le serveur de test " + guild.getName() + "."),
                    error -> System.out.println("❌ Erreur lors de la synchronisation des commandes : " + error.getMessage())
            );
        } else {
            registerGlobally(event, configCommand, configListeCommand, configSupprimerCommand, configReactionsCommand, postPhotoCommand);
        }
    }

    private void registerGlobally(ReadyEvent event, SlashCommandData... commands) {
        event.getJDA().updateCommands().addCommands(commands).queue(
                cmds -> System.out.println("🔄 " + cmds.size() + " commande(s) slash synchronisée(s) globalement (propagation possible sous ~1h)."),
                error -> System.out.println("❌ Erreur lors de la synchronisation des commandes : " + error.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "config" -> handleConfig(event);
            case "config-liste" -> handleConfigListe(event);
            case "config-supprimer" -> handleConfigSupprimer(event);
            case "config-reactions" -> handleConfigReactions(event);
            case "post-photo" -> handlePostPhoto(event);
        }
    }

    // --- Autocomplétion du champ "nom" (config existantes) ---
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("nom")) {
            return;
        }
        JSONObject profiles = ConfigManager.getProfiles(ConfigManager.loadRoot());
        String typed = event.getFocusedOption().getValue().toLowerCase();

        List<Command.Choice> choices = new ArrayList<>();
        for (String name : profiles.keySet()) {
            if (name.toLowerCase().contains(typed) && choices.size() < 25) {
                choices.add(new Command.Choice(name, name));
            }
        }
        event.replyChoices(choices).queue();
    }

    // --- Réponse Components V2 : un simple bloc de texte, toujours éphémère ---
    private void replyV2(IReplyCallback event, String text) {
        event.replyComponents(TextDisplay.of(text)).useComponentsV2().setEphemeral(true).queue();
    }

    // --- Réponse Components V2 avec un menu déroulant sous le texte (utilisé par /post-photo) ---
    private void replyV2WithMenu(IReplyCallback event, String text, StringSelectMenu menu) {
        Container container = Container.of(TextDisplay.of(text), ActionRow.of(menu));
        event.replyComponents(container).useComponentsV2().setEphemeral(true).queue();
    }

    // --- /config ---
    private void handleConfig(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            replyV2(event, "❌ Cette commande doit être utilisée sur un serveur.");
            return;
        }
        if (!isAdmin(event)) {
            replyV2(event, "❌ Vous devez être administrateur pour exécuter cette commande.");
            return;
        }

        String nom = event.getOption("nom").getAsString().trim();
        if (nom.isEmpty()) {
            replyV2(event, "❌ Le nom de la configuration ne peut pas être vide.");
            return;
        }

        JSONObject root = ConfigManager.loadRoot();
        JSONObject profiles = ConfigManager.getProfiles(root);
        boolean isNew = !profiles.has(nom);

        OptionMapping salonOpt = event.getOption("salon");
        if (isNew && salonOpt == null) {
            replyV2(event, "❌ Le paramètre `salon` est obligatoire pour créer une nouvelle configuration nommée **" + nom + "**.");
            return;
        }

        JSONObject profile = ConfigManager.getOrCreateProfile(profiles, nom);
        StringBuilder changes = new StringBuilder();

        if (salonOpt != null) {
            TextChannel salon = salonOpt.getAsChannel().asTextChannel();
            profile.put("target_channel_id", salon.getIdLong());
            changes.append("• **Salon :** ").append(salon.getAsMention()).append("\n");
        }

        OptionMapping roleOpt = event.getOption("role");
        if (roleOpt != null) {
            Role role = roleOpt.getAsRole();
            profile.put("ping_role_id", role.getIdLong());
            changes.append("• **Rôle ping :** ").append(role.getAsMention()).append("\n");
        }

        OptionMapping titreOpt = event.getOption("titre");
        if (titreOpt != null) {
            profile.put("title", titreOpt.getAsString());
            changes.append("• **Titre :** ").append(titreOpt.getAsString()).append("\n");
        }

        OptionMapping texteOpt = event.getOption("texte");
        if (texteOpt != null) {
            profile.put("text_template", texteOpt.getAsString());
            changes.append("• **Texte :** ").append(texteOpt.getAsString()).append("\n");
        }

        OptionMapping couleurOpt = event.getOption("couleur");
        if (couleurOpt != null) {
            String cleaned = couleurOpt.getAsString().trim();
            if (cleaned.startsWith("#")) cleaned = cleaned.substring(1);
            if (!HEX_COLOR_PATTERN.matcher(cleaned).matches()) {
                replyV2(event, "❌ Couleur invalide. Utilisez un format hexadécimal, ex: `2f3136` ou `#ff0000`.");
                return;
            }
            profile.put("color", cleaned);
            changes.append("• **Couleur :** #").append(cleaned).append("\n");
        }

        ConfigManager.saveRoot(root);

        if (changes.isEmpty()) {
            Long channelId = ConfigManager.getLongOrNull(profile, "target_channel_id");
            Long roleId = ConfigManager.getLongOrNull(profile, "ping_role_id");
            JSONArray reactions = profile.has("reactions") ? profile.getJSONArray("reactions") : new JSONArray();
            String reactionsSummary = reactions.isEmpty() ? "Aucune (voir `/config-reactions`)" : joinReactions(reactions);
            String summary = "ℹ️ **Configuration \"" + nom + "\" :**\n"
                    + "• Salon : " + (channelId != null ? "<#" + channelId + ">" : "Non configuré") + "\n"
                    + "• Rôle ping : " + (roleId != null ? "<@&" + roleId + ">" : "Aucun") + "\n"
                    + "• Titre : " + profile.getString("title") + "\n"
                    + "• Texte : " + profile.getString("text_template") + "\n"
                    + "• Couleur : #" + profile.getString("color") + "\n"
                    + "• Réactions : " + reactionsSummary + "\n\n"
                    + "Fournissez un ou plusieurs paramètres à cette commande pour les modifier. Pour les réactions, utilisez `/config-reactions`.";
            replyV2(event, summary);
            return;
        }

        String verb = isNew ? "créée" : "mise à jour";
        replyV2(event, "✅ **Configuration \"" + nom + "\" " + verb + " !**\n" + changes);
    }

    // --- /config-liste ---
    private void handleConfigListe(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            replyV2(event, "❌ Cette commande doit être utilisée sur un serveur.");
            return;
        }
        if (!isAdmin(event)) {
            replyV2(event, "❌ Vous devez être administrateur pour exécuter cette commande.");
            return;
        }

        JSONObject profiles = ConfigManager.getProfiles(ConfigManager.loadRoot());
        if (profiles.isEmpty()) {
            replyV2(event, "ℹ️ Aucune configuration n'existe encore. Utilisez `/config` pour en créer une.");
            return;
        }

        StringBuilder sb = new StringBuilder("📋 **Configurations enregistrées (" + profiles.length() + ") :**\n\n");
        for (String name : profiles.keySet()) {
            JSONObject p = profiles.getJSONObject(name);
            Long channelId = ConfigManager.getLongOrNull(p, "target_channel_id");
            Long roleId = ConfigManager.getLongOrNull(p, "ping_role_id");
            JSONArray reactions = p.has("reactions") ? p.getJSONArray("reactions") : new JSONArray();
            sb.append("**").append(name).append("**\n");
            sb.append("• Salon : ").append(channelId != null ? "<#" + channelId + ">" : "non configuré").append("\n");
            sb.append("• Rôle ping : ").append(roleId != null ? "<@&" + roleId + ">" : "aucun").append("\n");
            sb.append("• Titre : ").append(p.getString("title")).append("\n");
            sb.append("• Réactions : ").append(reactions.isEmpty() ? "aucune" : joinReactions(reactions)).append("\n\n");
        }

        String result = sb.toString();
        if (result.length() > 3900) {
            result = result.substring(0, 3900) + "\n… (liste tronquée, trop de configurations)";
        }
        replyV2(event, result);
    }

    // --- /config-supprimer ---
    private void handleConfigSupprimer(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            replyV2(event, "❌ Cette commande doit être utilisée sur un serveur.");
            return;
        }
        if (!isAdmin(event)) {
            replyV2(event, "❌ Vous devez être administrateur pour exécuter cette commande.");
            return;
        }

        String nom = event.getOption("nom").getAsString().trim();
        JSONObject root = ConfigManager.loadRoot();
        JSONObject profiles = ConfigManager.getProfiles(root);

        if (!profiles.has(nom)) {
            replyV2(event, "⚠️ Aucune configuration nommée **" + nom + "** n'existe. Utilisez `/config-liste` pour voir la liste.");
            return;
        }

        profiles.remove(nom);
        ConfigManager.saveRoot(root);
        replyV2(event, "🗑️ Configuration **" + nom + "** supprimée.");
    }

    // --- /config-reactions : ajouter/supprimer/vider/lister les réactions d'une configuration ---
    private void handleConfigReactions(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            replyV2(event, "❌ Cette commande doit être utilisée sur un serveur.");
            return;
        }
        if (!isAdmin(event)) {
            replyV2(event, "❌ Vous devez être administrateur pour exécuter cette commande.");
            return;
        }

        String nom = event.getOption("nom").getAsString().trim();
        JSONObject root = ConfigManager.loadRoot();
        JSONObject profiles = ConfigManager.getProfiles(root);

        if (!profiles.has(nom)) {
            replyV2(event, "⚠️ Aucune configuration nommée **" + nom + "** n'existe. Utilisez `/config-liste` pour voir la liste, ou `/config` pour la créer.");
            return;
        }

        JSONObject profile = profiles.getJSONObject(nom);
        JSONArray reactions = profile.has("reactions") ? profile.getJSONArray("reactions") : new JSONArray();
        profile.put("reactions", reactions);

        String action = event.getOption("action").getAsString();
        OptionMapping emojiOpt = event.getOption("emoji");

        switch (action) {
            case "ajouter" -> {
                if (emojiOpt == null) {
                    replyV2(event, "❌ Le paramètre `emoji` est obligatoire pour ajouter une réaction.");
                    return;
                }
                String emoji = emojiOpt.getAsString().trim();
                if (!isValidEmoji(emoji)) {
                    replyV2(event, "❌ Émoji invalide. Utilisez un émoji standard (ex: 🍴) ou un émoji custom du serveur sélectionné dans le picker Discord.");
                    return;
                }
                if (containsEmoji(reactions, emoji)) {
                    replyV2(event, "ℹ️ " + emoji + " est déjà dans les réactions de **" + nom + "**.");
                    return;
                }
                if (reactions.length() >= MAX_REACTIONS_PER_PROFILE) {
                    replyV2(event, "❌ Limite de " + MAX_REACTIONS_PER_PROFILE + " réactions atteinte pour **" + nom + "**.");
                    return;
                }
                reactions.put(emoji);
                ConfigManager.saveRoot(root);
                replyV2(event, "✅ " + emoji + " ajouté aux réactions de **" + nom + "**.");
            }
            case "supprimer" -> {
                if (emojiOpt == null) {
                    replyV2(event, "❌ Le paramètre `emoji` est obligatoire pour supprimer une réaction.");
                    return;
                }
                String emoji = emojiOpt.getAsString().trim();
                int index = indexOfEmoji(reactions, emoji);
                if (index == -1) {
                    replyV2(event, "⚠️ " + emoji + " n'est pas dans les réactions de **" + nom + "**.");
                    return;
                }
                reactions.remove(index);
                ConfigManager.saveRoot(root);
                replyV2(event, "🗑️ " + emoji + " retiré des réactions de **" + nom + "**.");
            }
            case "vider" -> {
                profile.put("reactions", new JSONArray());
                ConfigManager.saveRoot(root);
                replyV2(event, "🗑️ Toutes les réactions de **" + nom + "** ont été supprimées.");
            }
            case "lister" -> {
                if (reactions.isEmpty()) {
                    replyV2(event, "ℹ️ **" + nom + "** n'a aucune réaction configurée.");
                    return;
                }
                replyV2(event, "📋 **Réactions de \"" + nom + "\" :** " + joinReactions(reactions));
            }
        }
    }

    private boolean containsEmoji(JSONArray reactions, String emoji) {
        return indexOfEmoji(reactions, emoji) != -1;
    }

    private int indexOfEmoji(JSONArray reactions, String emoji) {
        for (int i = 0; i < reactions.length(); i++) {
            if (reactions.getString(i).equals(emoji)) {
                return i;
            }
        }
        return -1;
    }

    private String joinReactions(JSONArray reactions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reactions.length(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(reactions.getString(i));
        }
        return sb.toString();
    }

    private boolean isAdmin(SlashCommandInteractionEvent event) {
        return event.getMember() != null && event.getMember().hasPermission(Permission.ADMINISTRATOR);
    }

    private boolean isValidEmoji(String value) {
        String trimmed = value.trim();
        return UNICODE_EMOJI_PATTERN.matcher(trimmed).matches() || CUSTOM_EMOJI_PATTERN.matcher(trimmed).matches();
    }

    // --- /post-photo : étape 1, choix du salon via un menu ---
    private void handlePostPhoto(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            replyV2(event, "❌ Cette commande doit être utilisée sur un serveur.");
            return;
        }

        Attachment image1 = event.getOption("image").getAsAttachment();
        OptionMapping image2Opt = event.getOption("image2");
        Attachment image2 = image2Opt != null ? image2Opt.getAsAttachment() : null;

        if (image1.getContentType() == null || !image1.getContentType().startsWith("image/")) {
            replyV2(event, "❌ La première image n'est pas un fichier image valide (JPG, PNG, GIF, etc.).");
            return;
        }
        if (image2 != null && (image2.getContentType() == null || !image2.getContentType().startsWith("image/"))) {
            replyV2(event, "❌ La deuxième image n'est pas un fichier image valide (JPG, PNG, GIF, etc.).");
            return;
        }

        JSONObject profiles = ConfigManager.getProfiles(ConfigManager.loadRoot());
        if (profiles.isEmpty()) {
            replyV2(event, "⚠️ Aucune configuration n'existe. Un administrateur doit d'abord utiliser `/config`.");
            return;
        }

        cleanupExpiredPending();

        List<String> imageUrls = new ArrayList<>();
        imageUrls.add(image1.getUrl());
        if (image2 != null) {
            imageUrls.add(image2.getUrl());
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        pendingPosts.put(requestId, new PendingPost(event.getUser().getIdLong(), imageUrls, System.currentTimeMillis()));

        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(SELECT_PREFIX + requestId)
                .setPlaceholder("Choisissez le salon de publication");

        int added = 0;
        for (String name : profiles.keySet()) {
            if (added >= 25) break;
            JSONObject profile = profiles.getJSONObject(name);
            Long channelId = ConfigManager.getLongOrNull(profile, "target_channel_id");
            TextChannel channel = channelId != null ? guild.getTextChannelById(channelId) : null;

            String label = channel != null ? "#" + channel.getName() : "⚠️ salon introuvable";
            menuBuilder.addOption(label, name, "Configuration : " + name);
            added++;
        }

        if (added == 0) {
            pendingPosts.remove(requestId);
            replyV2(event, "⚠️ Aucune configuration valide n'a été trouvée.");
            return;
        }

        String prompt = image2 != null ? "Choisissez le salon où publier vos photos :" : "Choisissez le salon où publier votre photo :";
        replyV2WithMenu(event, prompt, menuBuilder.build());
    }

    // --- /post-photo : étape 2, le membre choisit un salon dans le menu ---
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String customId = event.getComponentId();
        if (!customId.startsWith(SELECT_PREFIX)) {
            return;
        }

        String requestId = customId.substring(SELECT_PREFIX.length());
        PendingPost pending = pendingPosts.remove(requestId);

        if (pending == null || System.currentTimeMillis() - pending.createdAt() > PENDING_EXPIRY_MS) {
            replyV2(event, "⚠️ Cette sélection a expiré. Relancez `/post-photo`.");
            return;
        }
        if (event.getUser().getIdLong() != pending.requesterId()) {
            replyV2(event, "❌ Seule la personne ayant lancé `/post-photo` peut faire ce choix.");
            return;
        }

        Guild guild = event.getGuild();
        String profileName = event.getValues().get(0);
        JSONObject profiles = ConfigManager.getProfiles(ConfigManager.loadRoot());

        if (guild == null || !profiles.has(profileName)) {
            replyV2(event, "⚠️ Cette configuration n'existe plus.");
            return;
        }

        JSONObject profile = profiles.getJSONObject(profileName);
        Long channelId = ConfigManager.getLongOrNull(profile, "target_channel_id");
        TextChannel targetChannel = channelId != null ? guild.getTextChannelById(channelId) : null;

        if (targetChannel == null) {
            replyV2(event, "⚠️ Le salon configuré pour **" + profileName + "** est introuvable ou a été supprimé.");
            return;
        }

        // On accuse réception tout de suite ; le message éphémère sera mis à jour une fois la publication terminée.
        event.deferEdit().queue();

        String bodyText = profile.getString("text_template").replace("{user}", "<@" + pending.requesterId() + ">");
        Color accentColor = parseHexColorOrDefault(profile.getString("color"));

        List<MediaGalleryItem> galleryItems = new ArrayList<>();
        for (String url : pending.imageUrls()) {
            galleryItems.add(MediaGalleryItem.fromUrl(url));
        }

        Container container = Container.of(
                TextDisplay.of("# " + profile.getString("title")),
                TextDisplay.of(bodyText),
                MediaGallery.of(galleryItems)
        ).withAccentColor(accentColor.getRGB());

        Long roleId = ConfigManager.getLongOrNull(profile, "ping_role_id");
        String pingContent = roleId != null ? "<@&" + roleId + ">" : null;

        MessageCreateAction messageAction = targetChannel.sendMessageComponents(container).useComponentsV2();
        if (pingContent != null) {
            messageAction = messageAction.setContent(pingContent);
        }

        messageAction.queue(
                posted -> {
                    addReactions(posted, profile);
                    event.getHook().editOriginalComponents(TextDisplay.of("✅ Publié dans " + targetChannel.getAsMention() + " !"))
                            .useComponentsV2().queue();
                },
                error -> event.getHook().editOriginalComponents(TextDisplay.of("❌ Erreur lors de la publication : " + error.getMessage()))
                        .useComponentsV2().queue()
        );
    }

    private void cleanupExpiredPending() {
        Iterator<Map.Entry<String, PendingPost>> it = pendingPosts.entrySet().iterator();
        long now = System.currentTimeMillis();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAt() > PENDING_EXPIRY_MS) {
                it.remove();
            }
        }
    }

    // Ajout des réactions automatiques (liste configurée via /config-reactions — standards ou custom du serveur)
    private void addReactions(Message posted, JSONObject profile) {
        if (!profile.has("reactions")) {
            return;
        }
        JSONArray reactions = profile.getJSONArray("reactions");
        for (int i = 0; i < reactions.length(); i++) {
            String emoji = reactions.getString(i);
            posted.addReaction(Emoji.fromFormatted(emoji)).queue(
                    success -> {},
                    error -> System.out.println("⚠️ Erreur lors de l'ajout de la réaction " + emoji + " : " + error.getMessage())
            );
        }
    }

    private Color parseHexColorOrDefault(String hex) {
        try {
            return new Color(Integer.parseInt(hex, 16));
        } catch (Exception e) {
            return new Color(0x2f3136);
        }
    }
}
