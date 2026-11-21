# GameInstaller

Універсальний Android-розпаковувач інсталяторів Windows-ігор (Inno Setup + архіви FreeArc) без Winlator.
Призначений для підготовки ігрових даних для нативних портів. Використовуйте лише власні легальні копії ігор.

## Стан
- [x] Парсер заголовка Inno 5.x (Kotlin + Python-прототип): архіви, пароль, допоміжні файли, API ISDone.
- [ ] JNI: innoextract (рівень 1), unarc з AES (рівень 2), `listArchive` для методів блоків.
- [ ] Рівень 3: srep / precomp / packjpg.
- [ ] Вибір папки через SAF, прогрес, перевірка хешів.

## Перевірка на ПК
    python3 -I tools/inno_probe.py /шлях/до/Setup.exe

## Збірка
Через GitHub Actions (.github/workflows/build.yml): APK у артефактах. Локально: `gradle :app:assembleDebug`.
Примітка: проєкт створено без запуску Android SDK, тож перша збірка може потребувати дрібних правок.
