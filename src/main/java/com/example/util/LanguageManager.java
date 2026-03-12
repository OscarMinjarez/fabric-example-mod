package com.example.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Mapea los códigos de idioma de Minecraft a instrucciones de regionalismo para la IA.
 */
public class LanguageManager {

    private static final Map<String, LanguageProfile> PROFILES = new HashMap<>();
    
    static {
        // Español - Variantes
        PROFILES.put("es_mx", new LanguageProfile(
            "es_mx", "Español (México)", "español mexicano",
            "Habla en español natural de México. Tutea. " +
            "Evita expresiones de España ('tío', 'mola', 'vosotros'). " +
            "PROHIBIDO usar estas palabras/frases: 'ay caramba', 'ándale', 'arriba', 'órale', 'híjole', 'mija', 'mijo', 'compadre', 'hermano' (como muletilla). " +
            "Habla como un joven mexicano real en internet, relajado y directo. Sin caricaturas."
        ));
        
        PROFILES.put("es_es", new LanguageProfile(
            "es_es", "Español (España)", "español de España",
            "Habla en español natural de España. Puedes usar 'vosotros' y tutear. " +
            "Sé casual sin forzar modismos."
        ));
        
        PROFILES.put("es_ar", new LanguageProfile(
            "es_ar", "Español (Argentina)", "español argentino",
            "Habla en español natural de Argentina. Usa voseo ('vos sos', 'vos tenés'). " +
            "No uses 'tú'. Sé casual sin forzar modismos."
        ));
        
        PROFILES.put("es_cl", new LanguageProfile(
            "es_cl", "Español (Chile)", "español chileno",
            "Habla en español natural de Chile. Tutea. Sé casual sin forzar modismos."
        ));
        
        PROFILES.put("es_co", new LanguageProfile(
            "es_co", "Español (Colombia)", "español colombiano",
            "Habla en español natural de Colombia. Tutea. Sé casual sin forzar modismos."
        ));
        
        PROFILES.put("es_ve", new LanguageProfile(
            "es_ve", "Español (Venezuela)", "español venezolano",
            "Habla en español natural de Venezuela. Tutea. Sé casual sin forzar modismos."
        ));
        
        // Inglés - Variantes
        PROFILES.put("en_us", new LanguageProfile(
            "en_us", "English (US)", "American English",
            "Speak in natural casual American English. Use American spellings. Be casual like chatting on Discord."
        ));
        
        PROFILES.put("en_gb", new LanguageProfile(
            "en_gb", "English (UK)", "British English",
            "Speak in natural casual British English. Use British spellings (colour, favourite). Be casual."
        ));
        
        PROFILES.put("en_au", new LanguageProfile(
            "en_au", "English (Australia)", "Australian English",
            "Speak in natural casual Australian English. Be casual."
        ));
        
        // Portugués
        PROFILES.put("pt_br", new LanguageProfile(
            "pt_br", "Português (Brasil)", "português brasileiro",
            "Fale em português brasileiro natural e casual. Não use português de Portugal."
        ));
        
        PROFILES.put("pt_pt", new LanguageProfile(
            "pt_pt", "Português (Portugal)", "português europeu",
            "Fale em português europeu natural e casual."
        ));
        
        // Francés
        PROFILES.put("fr_fr", new LanguageProfile(
            "fr_fr", "Français", "français",
            "Parle en français naturel et décontracté. Comme entre amis sur Discord."
        ));
        
        PROFILES.put("fr_ca", new LanguageProfile(
            "fr_ca", "Français (Canada)", "français québécois",
            "Parle en français québécois naturel et décontracté."
        ));
        
        // Alemán
        PROFILES.put("de_de", new LanguageProfile(
            "de_de", "Deutsch", "Deutsch",
            "Sprich in natürlichem, lockerem Deutsch. Wie unter Freunden im Chat."
        ));
        
        // Italiano
        PROFILES.put("it_it", new LanguageProfile(
            "it_it", "Italiano", "italiano",
            "Parla in italiano naturale e colloquiale. Come tra amici in chat."
        ));
        
        // Japonés
        PROFILES.put("ja_jp", new LanguageProfile(
            "ja_jp", "日本語", "日本語",
            "自然でカジュアルな日本語で話して。友達とチャットしてるみたいに。"
        ));
        
        // Coreano
        PROFILES.put("ko_kr", new LanguageProfile(
            "ko_kr", "한국어", "한국어",
            "자연스럽고 캐주얼한 한국어로 이야기해. 친구와 채팅하듯이."
        ));
        
        // Chino simplificado
        PROFILES.put("zh_cn", new LanguageProfile(
            "zh_cn", "简体中文", "简体中文",
            "用自然轻松的中文聊天。像和朋友在聊天一样。"
        ));
        
        // Chino tradicional
        PROFILES.put("zh_tw", new LanguageProfile(
            "zh_tw", "繁體中文", "繁體中文",
            "用自然輕鬆的中文聊天。像和朋友在聊天一樣。"
        ));
        
        // Ruso
        PROFILES.put("ru_ru", new LanguageProfile(
            "ru_ru", "Русский", "русский",
            "Говори на естественном разговорном русском. Как между друзьями в чате."
        ));
        
        // Polaco
        PROFILES.put("pl_pl", new LanguageProfile(
            "pl_pl", "Polski", "polski",
            "Mów naturalnym, luźnym polskim. Jak między przyjaciółmi na czacie."
        ));
    }
    
    public static LanguageProfile getProfile(String languageCode) {
        // Intentar coincidencia exacta
        if (PROFILES.containsKey(languageCode)) {
            return PROFILES.get(languageCode);
        }
        
        // Intentar coincidencia parcial (solo idioma base)
        String baseCode = languageCode.split("_")[0];
        for (String key : PROFILES.keySet()) {
            if (key.startsWith(baseCode + "_")) {
                return PROFILES.get(key);
            }
        }
        
        // Default: español mexicano
        return PROFILES.get("es_mx");
    }
    
    public static LanguageProfile getProfileOrDefault(String languageCode, String defaultCode) {
        LanguageProfile profile = PROFILES.get(languageCode);
        if (profile != null) return profile;
        
        profile = PROFILES.get(defaultCode);
        if (profile != null) return profile;
        
        return PROFILES.get("es_mx");
    }
    
    public record LanguageProfile(
        String code,
        String displayName,
        String languageName,
        String promptInstructions
    ) {
        // Frases localizadas para usar en prompts
        public String getGreetingPrompt() {
            return switch (code.split("_")[0]) {
                case "en" -> "Someone new just connected. Greet them and you MUST ask 'what's your name?' or 'what should I call you?'. You MUST ask for their name.";
                case "pt" -> "Alguém novo conectou. Cumprimente e DEVE perguntar 'qual seu nome?' ou 'como te chamo?'. OBRIGATÓRIO perguntar o nome.";
                case "fr" -> "Quelqu'un de nouveau s'est connecté. Salue et DOIS demander 'comment tu t'appelles?' ou 'c'est quoi ton nom?'. OBLIGATOIRE.";
                case "de" -> "Jemand Neues ist eingetreten. Begrüße sie and frage UNBEDINGT 'wie heißt du?' oder 'wie soll ich dich nennen?'. PFLICHT.";
                case "it" -> "Qualcuno di nuovo si è connesso. Saluta e DEVI chiedere 'come ti chiami?' o 'qual è il tuo nome?'. OBBLIGATORIO.";
                case "ja" -> "新しい人が接続しました。挨拶して、必ず「お名前は？」と聞いてください。";
                case "ko" -> "새로운 사람이 접속했어요. 인사하고 반드시 '이름이 뭐예요?'라고 물어보세요.";
                case "zh" -> "有新玩家加入了。打招呼并且必须问'你叫什么名字？'。必须问名字。";
                case "ru" -> "Кто-то новый подключился. Поприветствуй и ОБЯЗАТЕЛЬНО спроси 'как тебя зовут?'. Обязательно.";
                case "pl" -> "Ktoś nowy się połączył. Przywitaj się i MUSISZ zapytać 'jak masz na imię?'. OBOWIĄZKOWO.";
                default -> "Alguien nuevo se conectó. Salúdalo y DEBES preguntarle '¿cómo te llamas?' o '¿cuál es tu nombre?'. Es OBLIGATORIO que le preguntes su nombre.";
            };
        }
        
        public String getNameReceivedPrompt(String playerName) {
            return switch (code.split("_")[0]) {
                case "en" -> "The player just told you their name is " + playerName + ". Greet them by name in a casual, friendly way.";
                case "pt" -> "O jogador disse que se chama " + playerName + ". Cumprimente pelo nome de forma casual e amigável.";
                case "fr" -> "Le joueur a dit qu'il s'appelle " + playerName + ". Salue-le par son nom de façon décontractée et amicale.";
                case "de" -> "Der Spieler sagte, er heißt " + playerName + ". Begrüße ihn locker und freundlich mit Namen.";
                case "it" -> "Il giocatore ha detto di chiamarsi " + playerName + ". Salutalo per nome in modo casual e amichevole.";
                case "ja" -> "プレイヤーの名前は" + playerName + "だそうです。名前で呼んでフレンドリーに挨拶して。";
                case "ko" -> "플레이어 이름이 " + playerName + "라고 했어요. 이름을 불러서 친근하게 인사하세요.";
                case "zh" -> "玩家说他们叫" + playerName + "。用名字友好地打招呼。";
                case "ru" -> "Игрок сказал, что его зовут " + playerName + ". Поприветствуй по имени дружелюбно.";
                case "pl" -> "Gracz powiedział, że ma na imię " + playerName + ". Przywitaj się po imieniu przyjaźnie.";
                default -> "El jugador te dijo que se llama " + playerName + ". Salúdalo por su nombre de forma casual y amigable.";
            };
        }
        
        public String getReturningPlayerPrompt(String playerName) {
            return switch (code.split("_")[0]) {
                case "en" -> playerName + " just came back to the world. Welcome them like a friend you already know.";
                case "pt" -> playerName + " voltou ao mundo. Dê boas-vindas como a um amigo que você já conhece.";
                case "fr" -> playerName + " vient de revenir dans le monde. Accueille-le comme un ami que tu connais déjà.";
                case "de" -> playerName + " ist gerade zurückgekommen. Begrüße wie einen Freund, den du schon kennst.";
                case "it" -> playerName + " è appena tornato nel mondo. Accoglilo come un amico che già conosci.";
                case "ja" -> playerName + "が戻ってきたよ。もう知ってる友達として迎えて。";
                case "ko" -> playerName + "님이 돌아왔어요. 이미 아는 친구처럼 반겨주세요.";
                case "zh" -> playerName + "回来了。像老朋友一样欢迎他们。";
                case "ru" -> playerName + " вернулся в мир. Поприветствуй как друга, которого уже знаешь.";
                case "pl" -> playerName + " właśnie wrócił do świata. Przywitaj jak przyjaciela, którego już znasz.";
                default -> playerName + " acaba de volver al mundo. Dale la bienvenida como a un amigo que ya conoces.";
            };
        }
        
        public String getPlayerNameContext(String playerName) {
            return switch (code.split("_")[0]) {
                case "en" -> " The player's name is " + playerName + ".";
                case "pt" -> " O nome do jogador é " + playerName + ".";
                case "fr" -> " Le joueur s'appelle " + playerName + ".";
                case "de" -> " Der Spieler heißt " + playerName + ".";
                case "it" -> " Il giocatore si chiama " + playerName + ".";
                case "ja" -> " プレイヤーの名前は" + playerName + "です。";
                case "ko" -> " 플레이어 이름은 " + playerName + "입니다.";
                case "zh" -> " 玩家叫" + playerName + "。";
                case "ru" -> " Игрока зовут " + playerName + ".";
                case "pl" -> " Gracz ma na imię " + playerName + ".";
                default -> " El jugador se llama " + playerName + ".";
            };
        }
    }
}
