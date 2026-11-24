# GpTMax — Local-first AI Coding Studio for Android

<p align="center"><b>استوديو برمجة ذكي يعمل محلياً على هاتفك — بخصوصيتك الكاملة</b></p>

---

## بالعربية

هذا هو التطبيق الكامل وفق **استراتيجية A+** من تقرير التطوير (Android local-first + جسر سحابي BYOK).

### ما الذي يعمل في هذا الإصدار؟

| المكوّن | الحالة | الملفات |
|---|---|---|
| واجهة Compose كاملة (5 شاشات، RTL، عربي/إنجليزي) | جاهزة | `ui/` |
| محرك استدلال محلي عبر JNI + llama.cpp (بث تدفقي، إيقاف تعاوني) | جاهزة | `cpp/gptmax_jni.cpp`، `bridge/` |
| حلقة الوكيل: تخطيط → جمع سياق → تنفيذ → تحقق → بوابة موافقة بشرية | جاهزة | `core/agent/` |
| جسر سحابي BYOK (متوافق OpenAI): GLM Coding Plan / DeepSeek / OpenRouter / Ollama | جاهزة | `core/router/CloudBridge.kt` |
| موجّه المهام حسب الاستراتيجية (محلي للمحادثة، سحابي للتخطيط عند تفعيله) | جاهزة | `core/router/InferenceRouter.kt` |
| أدوات الوكيل: list_dir / read_file / write_file / delete_file / grep / finish | جاهزة | `core/agent/ToolRegistry.kt` |
| مساحة عمل SAF آمنة (مجلد مشروع واحد، بدون أذونات تخزين واسعة) | جاهزة | `data/repo/WorkspaceRepository.kt` |
| كتالوج نماذج GGUF + مدير تنزيل مع تقدّم (Qwen2.5-Coder 0.5B→7B، SmolLM2) | جاهزة | `data/repo/ModelRepository.kt` |
| تخزين Room (محادثات + عمليات الوكيل) وإعدادات DataStore | جاهزة | `data/db/`، `data/settings/` |

### البدء السريع

1. افتح المجلد في Android Studio (Ladybug+) وانتظر مزامنة Gradle.
2. أضف llama.cpp وفق `app/src/main/cpp/VENDOR.md` (أمر واحد).
3. شغّل على جهاز arm64 (Android 8.0+).
4. تبويب **النماذج** → نزّل `Qwen2.5-Coder-3B-Instruct Q4_K_M` (‎≈1.9GB) → **Load**.
5. تبويب **الإعدادات** → اختر مجلد مشروع → (اختياري) أضف مفتاح BYOK للسحابة.
6. جرّب المحادثة أو أنجز مهمة عبر تبويب **الوكيل** — كل كتابة ملف تمر عبر بوابة موافقتك.

### خريطة الكود إلى خطة التطوير

- **المرحلة P0–P1 (Studio الأساس)**: الواجهات + المحرر + المحرك المحلي — **منجزة هنا**.
- **المرحلة P2 (Agent Core + BYOK)**: الحلقة الكاملة والجسر السحابي — **منجزة هنا**.
- **المرحلة P3 (Runtime مستقل + أدوات متقدمة)**: تشغيل أوامر shell/اختبارات داخل sandbox — التمديد الطبيعي `ToolRegistry`.
- **المرحلة P4 (RAG فهرسة الكود)**: استبدل `ContextBuilder.repoMap` بفهرس embeddings.
- **المرحلة P5 (مختبر التكميم — LookAtMyQuant)**: توليد بناءات GGUF أذكى عبر مترجم `lookatmyquant` (انظر `docs/MODELS.md`).

---

## In English

Complete Android implementation of the **A+ strategy**: a local-first AI coding
studio. Inference runs on-device through llama.cpp (JNI, arm64) with
Qwen2.5-Coder GGUF quantized models; heavier planning/validation steps route
through a **BYOK** cloud bridge (OpenAI-compatible: GLM Coding Plan, DeepSeek,
OpenRouter, or an Ollama PC on your LAN). Every file write by the agent passes
a human approval gate.

```
Jetpack Compose (Material 3, RTL, ar/en)
        │
   Agent loop: Plan → Context → Act → Validate → [approval gate]
        │                              │
 InferenceRouter ──────┬────── LocalEngine (JNI/llama.cpp, ChatML, GGUF Q4_K_M)
                       └────── CloudBridge (BYOK, SSE streaming)
        │
 Room (chat + runs) · DataStore (keys/prefs) · SAF workspace
```

Build: open in Android Studio, vendor llama.cpp (one command, see
`app/src/main/cpp/VENDOR.md`), run on an arm64 device (Android 8+).
**No local toolchain?** Push to GitHub — the bundled workflow
(`.github/workflows/build.yml`) vendors llama.cpp, builds the arm64 debug
APK and uploads it as a downloadable artifact on every push.
Model catalog and quantization guidance (including the LookAtMyQuant
compiler path) live in `docs/MODELS.md`.

License: MIT. The llama.cpp dependency keeps its own MIT license; base model
licenses apply to each GGUF you ship.
