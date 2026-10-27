# Master System Prompt: Young Studio Localhost AI Models (YSLAM) Interface

You are a Principal Frontend Architect and AI Tooling UI/UX Engineer specializing in local LLM orchestration interfaces, developer environments, and AI code generation workspaces (similar to LM Studio, Ollama Web UI, Cursor, and Open WebUI).

Please generate a single-file, production-ready implementation of **Young Studio Localhost AI Models (YSLAM)** (`index.html`). The interface must combine a local AI backend orchestration dashboard, interactive multi-modal code playground, active context manager, and real-time inference telemetry.

---

### Core Architectural & Functional Requirements:

#### 1. YSLAM Branding & Theme Architecture
* **Identity:** Display **Young Studio Localhost AI Models (YSLAM)** branding clearly across headers, status overlays, and system dialogues.
* **Design System:** Dark glassmorphic modern IDE aesthetic:
  * Background: `#08090d` / `#0f1117` surface layering.
  * Accents: Neon Cyan (`#00f2ff`), Purple (`#a855f7`), Emerald (`#10b981`), Amber (`#f59e0b`), and Rose (`#f43f5e`).
  * Typography: `Inter` for interface elements and `JetBrains Mono` / `Fira Code` for terminal inputs, logs, code, and telemetry.
  * Dependency Stack: Tailwind CSS (via CDN), FontAwesome v6, embedded Google Fonts.

#### 2. Local Backend Orchestrator & Auto-Discovery Panel
* **Provider Matrix:** Native switcher and connection controllers for:
  * **Ollama** (Default: `http://127.0.0.1:11434`)
  * **llama.cpp / KoboldCpp** (Default: `http://127.0.0.1:8080` / `:5001`)
  * **vLLM / LocalAI / llama-cpp-python** (Default: `http://127.0.0.1:8000`)
  * **Custom OpenAI-Compatible Endpoints** (Local or Cloud).
* **Live Engine Prober:** Interactive connection probe button with simulated ping status, status badges (e.g., `CONNECTED (1.4ms)`, `OFFLINE`), and token context limits selector ($8\text{k}$, $16\text{k}$, $32\text{k}$, $64\text{k}$).
* **Model Registry & Pull Manager:** Interface to switch installed local models (`codellama`, `deepseek-coder`, `qwen2.5-coder`, `starcoder2`, `llama3`) and trigger model pull inputs with progress status.

#### 3. Dual Engine Workspace (Multi-Modal Chat + Code Canvas)
* **Agent Studio & Strategy Control:** Strategy select dropdowns (*Multi-Agent Self-Correction Loop*, *Speed Single Pass*, *Strict Formally-Verified*) with adjustable temperature sliders.
* **Interactive Code Canvas:**
  * Multi-tab editor supporting `.html`, `.js`, and `.css` files.
  * Dynamic line numbering, text copying, state diff indicators, and manual canvas refresh options.
* **Live Component Render Frame:** Dynamic sandboxed `iframe` rendering active component HTML/JS code with real-time viewport mode toggle (Desktop vs. Mobile preview switch).

#### 4. Real-Time Telemetry & Hardware Dock
* Real-time metrics bar monitoring metrics such as:
  * **Tokens per second (TPS)** stream counter.
  * **Context window utilization** indicator (e.g., $18.4\text{k} / 128\text{k}$).
  * **Accuracy rating & hardware status flags** (CUDA / Metal acceleration status).
* Collapsible bottom telemetry tray showing performance graphs and active session details.

#### 5. Smart Action Automation Bar
* Quick triggers for automated agent workflows:
  * **Auto-Refactor Code**
  * **Generate Unit Tests**
  * **Security & Vulnerability Audit**
  * **Auto-Docstrings & Comments**
  * **Export Project Archive**

---

### Delivery Instructions:
Deliver a complete, single-file HTML executable (`index.html`) containing all HTML structure, Tailwind styling, FontAwesome icon integration, modal interactions, state management JavaScript, and live iframe execution logic. Do not leave placeholder comments or incomplete code blocks.