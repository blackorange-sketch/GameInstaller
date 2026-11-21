# third_party

Сюди додаються сабмодулі (на ПК, де є мережа):

    git submodule add https://github.com/dscharrer/innoextract third_party/innoextract
    # unarc: розпаковувач FreeArc (лише розпаковка), із вихідників FreeArc 0.67 / форків на GitHub

Потім підключити їх у `app/src/main/cpp/CMakeLists.txt` і зробити JNI-обгортки:
- `listArchive(path, password)` повертає методи блоків (аналог `unarc v`);
- `extract(path, password, destDir, progressCb)`.

Перевірте ліцензії: innoextract (zlib), FreeArc/unarc (GPL), precomp (GPL), srep (див. його умови).
