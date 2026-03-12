# 🤖 Ollama Bot - Minecraft AI Companion

Un mod de Fabric para Minecraft 1.21.6 que añade un compañero IA personalizado para cada jugador, impulsado por [Ollama](https://ollama.ai/).

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.6-green)
![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.18.2-blue)
![License](https://img.shields.io/badge/License-CC0-lightgrey)

## ✨ Características

### 🎭 Personalidad única por jugador
- Cada jugador obtiene su propio compañero bot con **nombre, personalidad y estilo de hablar únicos**
- La personalidad se genera automáticamente usando IA cuando el jugador se conecta por primera vez
- Se guarda por mundo y por jugador

### 🌍 Soporte multiidioma
El bot detecta automáticamente el idioma del juego del jugador y responde en ese idioma.

| Idioma | Código | Ejemplo de nombre |
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

### 👁️ Observadores del juego
El bot reacciona a eventos del juego en tiempo real:

- **🗡️ `CombatObserver`**: muertes, daño fuerte y kills de mobs
- **🌎 `WorldObserver`**: cambios de bioma, clima, dimensión y hora del día
- **❤️ `PlayerStatusObserver`**: salud baja, hambre, peligro cercano y hallazgos importantes
- **💬 `ChatObserver`**: registro del nombre y conversación con el jugador
- **🌐 `LanguageObserver`**: detección del idioma del cliente

### 🧠 Arquitectura Blackboard
Sistema de comunicación centralizado entre observadores:
- Cola de eventos con prioridades
- Cooldowns para evitar spam
- Historial de conversación por jugador
- Persistencia por mundo

## 📦 Requisitos

- **Minecraft**: 1.21.6
- **Fabric Loader**: 0.18.2+
- **Fabric API**: 0.128.2+
- **Java**: 21
- **Ollama**: instalado y ejecutándose localmente
- **Modelo LLM**: `llama3.2` (u otro compatible)

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
1. Compila o descarga el `.jar` del mod
2. Colócalo en la carpeta `mods/` de tu instalación de Minecraft Fabric 1.21.6
3. Inicia el juego

## ⚙️ Configuración

El archivo de configuración se guarda como `ollama_bot.json` en el directorio de configuración de Fabric.

Ubicaciones típicas:
- Desarrollo: `run/config/ollama_bot.json`
- Instalación normal: `.minecraft/config/ollama_bot.json`

Ejemplo:
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

### Opciones de configuración

| Opción | Descripción | Default |
|--------|-------------|---------|
| `ollama.url` | URL del servidor Ollama | `http://localhost:11434` |
| `ollama.model` | Modelo LLM a usar | `llama3.2` |
| `ollama.timeoutSeconds` | Timeout para respuestas normales | `30` |
| `ollama.personalityTimeoutSeconds` | Timeout para generar personalidades | `60` |
| `cooldowns.highEventSeconds` | Cooldown entre eventos importantes | `8` |
| `cooldowns.spontaneousSeconds` | Cooldown entre comentarios espontáneos | `120` |
| `history.maxMessages` | Máximo de mensajes en historial | `20` |
| `behavior.chatPrefix` | Prefijo del nombre del bot | `§9` |
| `behavior.chatSuffix` | Sufijo aplicado al mensaje del bot | `§f` |
| `behavior.showThinkingIndicator` | Campo presente en config; actualmente reservado | `true` |
| `behavior.thinkingMessage` | Campo presente en config; actualmente reservado | `...` |
| `language` | Campo global presente en config; el idioma real se detecta por jugador | `es` |

## 📁 Estructura del proyecto

```text
src/main/java/com/example/
├── ExampleMod.java
├── ai/
│   ├── OllamaClient.java
│   ├── OllamaHealthCheck.java
│   ├── PersonalityGenerator.java
│   └── PromptManager.java
├── blackboard/
│   ├── Blackboard.java
│   └── BotEvent.java
├── config/
│   └── ModConfig.java
├── controller/
│   └── BotController.java
├── data/
│   └── DataManager.java
├── mixin/
│   └── ExampleMixin.java
├── observers/
│   ├── ChatObserver.java
│   ├── CombatObserver.java
│   ├── LanguageObserver.java
│   ├── PlayerStatusObserver.java
│   └── WorldObserver.java
└── util/
    ├── LanguageManager.java
    └── NameParser.java

src/client/java/com/example/
├── ExampleModClient.java
└── mixin/client/
    └── ExampleClientMixin.java
```

## 💾 Datos del jugador

Los datos se guardan por mundo en archivos con este formato:

```text
ollama_bot_[NombreMundo]_[hash].json
```

Ejemplo simplificado:
```json
{
  "players": {
    "uuid-del-jugador": {
      "name": "Oscar",
      "history": [
        {"role": "user", "content": "hola"},
        {"role": "assistant", "content": "¡Hola, Oscar! ¿Qué tal?"}
      ],
      "personality": {
        "name": "Luisa",
        "gender": "female",
        "age": "23",
        "traits": "amigable, curiosa, algo impaciente",
        "speakingStyle": "casual y directa"
      }
    }
  }
}
```

## 🎮 Uso en juego

1. **Primera conexión**: el bot genera una personalidad para ese jugador y pide tu nombre
2. **Registro**: respondes en el chat con tu nombre
3. **Siguientes conexiones**: si ya estabas registrado, te reconoce y te saluda como jugador recurrente
4. **Conversación**: puedes hablar con tu bot escribiendo en el chat
5. **Reacciones automáticas**: el bot comentará sobre tu situación actual y eventos del mundo

## 📋 Eventos detectados

| Evento | Impacto | Descripción |
|--------|---------|-------------|
| Muerte del jugador | 🔴 HIGH | El jugador murió |
| Salud crítica | 🔴 HIGH | Salud ≤ 3 corazones |
| Hambre crítica | 🔴 HIGH | Hambre ≤ 6 puntos |
| Jugador nuevo / registro | 🔴 HIGH | Flujo de saludo y registro de nombre |
| Peligro cercano | 🟡 NORMAL | Hay mobs hostiles cerca |
| Daño fuerte | 🟡 NORMAL | Recibió un golpe importante |
| Cambio de clima | 🟡 NORMAL | Empezó a llover o hay tormenta eléctrica |
| Cambio de dimensión | 🟡 NORMAL | Entró o volvió de Nether / End |
| Hallazgo importante | 🔴/🟡 HIGH/NORMAL | Diamantes, ancient debris, spawner, etc. |
| Cambio de bioma | 🟢 LOW | Entró a un bioma notable |
| Cambio de hora | 🟢 LOW | Amanecer / anochecer |
| Comentario espontáneo | 🟡 NORMAL | Comentario contextual sobre la situación del jugador |

## 🛠️ Desarrollo

### Compilar
```powershell
.\gradlew.bat build
```

### Ejecutar cliente de desarrollo
```powershell
.\gradlew.bat runClient
```

### Generar sources
```powershell
.\gradlew.bat genSources
```

## 🤝 Contribuir

1. Haz un fork del repositorio
2. Crea una rama (`git checkout -b feature/nueva-caracteristica`)
3. Haz commit de tus cambios (`git commit -am 'Añade nueva característica'`)
4. Haz push a la rama (`git push origin feature/nueva-caracteristica`)
5. Abre un Pull Request

## 📄 Licencia

Este proyecto está disponible bajo la licencia CC0. Siéntete libre de aprender de él e incorporarlo en tus propios proyectos.

## 🙏 Créditos

- [Fabric](https://fabricmc.net/) - Mod loader
- [Ollama](https://ollama.ai/) - LLM local
- [Llama](https://llama.meta.com/) - Modelo de lenguaje

---

**¿Problemas?** Asegúrate de que Ollama esté ejecutándose con `ollama serve` y que el modelo esté descargado con `ollama pull llama3.2`.
