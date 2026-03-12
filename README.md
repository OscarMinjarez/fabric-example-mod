# 🤖 Ollama Bot - Minecraft AI Companion

Un mod de Fabric para Minecraft 1.21.11 que añade un compañero IA personalizado para cada jugador, impulsado por [Ollama](https://ollama.ai/).

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.18.2-blue)
![License](https://img.shields.io/badge/License-CC0-lightgrey)

## ✨ Características

### 🎭 Personalidad Única por Jugador
- Cada jugador obtiene su propio compañero bot con **nombre, personalidad y estilo de hablar únicos**
- La personalidad se genera automáticamente usando IA cuando el jugador se conecta por primera vez
- Se guarda permanentemente para cada jugador

### 🌍 Soporte Multiidioma
El bot detecta automáticamente el idioma del juego del jugador y responde en ese idioma:

| Idioma | Código | Ejemplo de Nombre |
|--------|--------|-------------------|
| Español (México) | `es_mx` | María, Carlos, Sofía |
| Español (España) | `es_es` | Pablo, Carmen, Javier |
| Español (Argentina) | `es_ar` | Martín, Valentina |
| English (US) | `en_us` | James, Emma, Michael |
| English (UK) | `en_gb` | Oliver, Charlotte |
| Português (Brasil) | `pt_br` | João, Ana, Lucas |
| Français | `fr_fr` | Pierre, Marie, Louis |
| Deutsch | `de_de` | Hans, Anna, Max |
| Italiano | `it_it` | Marco, Giulia, Luca |
| 日本語 | `ja_jp` | Yuki, Kenji, Sakura |
| 한국어 | `ko_kr` | Min-jun, Ji-eun |
| 中文 | `zh_cn` | Wei, Mei, Jun |
| Русский | `ru_ru` | Alexei, Natasha |
| Polski | `pl_pl` | Jan, Anna, Marek |

### 👁️ Observadores del Juego
El bot reacciona a eventos del juego en tiempo real:

- **🗡️ CombatObserver**: Detecta peleas, muertes, y combate con mobs
- **🌎 WorldObserver**: Cambios de bioma, clima, hora del día
- **❤️ PlayerStatusObserver**: Salud baja, hambre, peligro
- **💬 ChatObserver**: Conversaciones con el jugador
- **🌐 LanguageObserver**: Detecta el idioma del cliente

### 🧠 Arquitectura Blackboard
Sistema de comunicación centralizado entre observadores:
- Cola de eventos con prioridades
- Cooldowns inteligentes para evitar spam
- Historial de conversación por jugador

## 📦 Requisitos

- **Minecraft**: 1.21.11
- **Fabric Loader**: 0.18.2+
- **Fabric API**: 0.139.4+
- **Ollama**: Instalado y ejecutándose localmente
- **Modelo LLM**: llama3.2 (u otro compatible)

## 🚀 Instalación

### 1. Instalar Ollama
```bash
# Windows (PowerShell)
winget install Ollama.Ollama

# macOS
brew install ollama

# Linux
curl -fsSL https://ollama.ai/install.sh | sh
```

### 2. Descargar un modelo
```bash
ollama pull llama3.2
```

### 3. Iniciar Ollama
```bash
ollama serve
```

### 4. Instalar el mod
1. Descarga el archivo `.jar` del mod
2. Colócalo en la carpeta `mods/` de tu instalación de Minecraft
3. ¡Listo!

## ⚙️ Configuración

El archivo de configuración se encuentra en: `config/ollama_bot.json`

```json
{
  "ollama": {
    "url": "http://localhost:11434",
    "model": "llama3.2",
    "timeoutSeconds": 30,
    "personalityTimeoutSeconds": 60
  },
  "cooldowns": {
    "highEventSeconds": 8,
    "spontaneousSeconds": 120
  },
  "history": {
    "maxMessages": 20
  },
  "behavior": {
    "showThinkingIndicator": true,
    "thinkingMessage": "...",
    "chatPrefix": "§9",
    "chatSuffix": "§f"
  },
  "language": "es"
}
```

### Opciones de Configuración

| Opción | Descripción | Default |
|--------|-------------|---------|
| `ollama.url` | URL del servidor Ollama | `http://localhost:11434` |
| `ollama.model` | Modelo LLM a usar | `llama3.2` |
| `ollama.timeoutSeconds` | Timeout para respuestas | `30` |
| `cooldowns.highEventSeconds` | Cooldown entre eventos importantes | `8` |
| `cooldowns.spontaneousSeconds` | Cooldown entre comentarios espontáneos | `120` |
| `history.maxMessages` | Máximo de mensajes en historial | `20` |
| `behavior.chatPrefix` | Prefijo del nombre del bot (color) | `§9` (azul) |

## 📁 Estructura del Proyecto

```
src/main/java/com/example/
├── ExampleMod.java           # Punto de entrada del mod
├── ai/
│   ├── OllamaClient.java     # Cliente HTTP para Ollama
│   ├── OllamaHealthCheck.java # Verificación de conexión
│   ├── PersonalityGenerator.java # Generador de personalidades
│   └── PromptManager.java    # Constructor de prompts
├── blackboard/
│   ├── Blackboard.java       # Sistema centralizado de estado
│   └── BotEvent.java         # Eventos del bot
├── config/
│   └── ModConfig.java        # Configuración del mod
├── controller/
│   └── BotController.java    # Controlador principal
├── data/
│   └── DataManager.java      # Persistencia de datos
├── observers/
│   ├── ChatObserver.java     # Observador de chat
│   ├── CombatObserver.java   # Observador de combate
│   ├── LanguageObserver.java # Observador de idioma
│   ├── PlayerStatusObserver.java # Observador de estado
│   └── WorldObserver.java    # Observador del mundo
└── util/
    ├── LanguageManager.java  # Perfiles de idioma
    └── NameParser.java       # Parser de nombres
```

## 💾 Datos del Jugador

Los datos se guardan por mundo en: `ollama_bot_[NombreMundo]_[hash].json`

```json
{
  "players": {
    "uuid-del-jugador": {
      "name": "NombreDelJugador",
      "history": [
        {"role": "user", "content": "hola"},
        {"role": "assistant", "content": "¡Hey! ¿Qué onda?"}
      ],
      "personality": {
        "name": "María",
        "gender": "female",
        "age": "23",
        "traits": "amigable, curiosa, algo impaciente",
        "speakingStyle": "casual y directa, usa emojis"
      }
    }
  }
}
```

## 🎮 Uso en Juego

1. **Primera conexión**: El bot generará una personalidad única y te saludará preguntando tu nombre
2. **Conversación**: Escribe en el chat para hablar con tu bot
3. **Reacciones automáticas**: El bot comentará sobre:
   - Cuando cambias de bioma
   - Si tu salud está baja
   - Cuando mueres
   - Cambios de clima
   - Y más...

## 🛠️ Desarrollo

### Compilar
```bash
./gradlew build
```

### Ejecutar cliente de desarrollo
```bash
./gradlew runClient
```

### Generar sources
```bash
./gradlew genSources
```

## 📋 Eventos Detectados

| Evento | Impacto | Descripción |
|--------|---------|-------------|
| Muerte del jugador | 🔴 HIGH | El jugador murió |
| Salud crítica | 🔴 HIGH | Salud < 4 corazones |
| Jugador nuevo | 🔴 HIGH | Primera conexión |
| Cambio de bioma | 🟡 NORMAL | Entró a nuevo bioma |
| Cambio de clima | 🟡 NORMAL | Empezó/paró de llover |
| Cambio de hora | 🟢 LOW | Amanecer/atardecer |

## 🤝 Contribuir

1. Fork el repositorio
2. Crea una rama (`git checkout -b feature/nueva-caracteristica`)
3. Commit tus cambios (`git commit -am 'Añade nueva característica'`)
4. Push a la rama (`git push origin feature/nueva-caracteristica`)
5. Abre un Pull Request

## 📄 Licencia

Este proyecto está disponible bajo la licencia CC0. Siéntete libre de aprender de él e incorporarlo en tus propios proyectos.

## 🙏 Créditos

- [Fabric](https://fabricmc.net/) - Mod loader
- [Ollama](https://ollama.ai/) - LLM local
- [Llama](https://llama.meta.com/) - Modelo de lenguaje

---

**¿Problemas?** Asegúrate de que Ollama esté ejecutándose (`ollama serve`) y que el modelo esté descargado (`ollama pull llama3.2`).
