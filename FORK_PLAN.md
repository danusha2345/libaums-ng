# libaums — план форка и доработок

> Рабочий документ форка. Анализ кодовой базы на ветке `develop` (HEAD `52167ed`, 2025-06-12), проиндексированной в GitNexus (2042 узла / 5212 рёбер). Все факты сверены по исходникам в клоне, а не по памяти.

## 0. Что это и зачем форк

`libaums` («Android USB Mass Storage») — библиотека для чтения/записи USB-накопителей (флешки, внешние HDD, **картоводы**) через USB-OTG **без root**, через Android USB Host API. Стек снизу вверх:

- **USB-транспорт** — `UsbMassStorageDevice` (claim интерфейса, поиск bulk IN/OUT). Два бэкенда: нативный Android (`usb/AndroidUsbCommunication`, без `.so`) и `libusbcommunication` (libusb через JNI).
- **SCSI поверх Bulk-Only Transport** — `driver/scsi/ScsiBlockDevice` (CBW/CSW, READ10/WRITE10, sense, recovery).
- **Разделы** — MBR + GPT (`partition/*`), плюс fallback «superfloppy» (`partition/fs/FileSystemPartitionTableCreator`) для неразмеченных карт.
- **ФС** — pluggable `FileSystemFactory` (`TreeSet` по приоритету). В ядре только **FAT32** (`fs/fat32/*`). Модуль `javafs` оборачивает jnode-fs (exFAT/NTFS/ext2/…) — но он **медленный и кривой на запись**.

Зачем форк: апстрим жив, но релиз заморожен на **0.10.0 (март 2023)**; все фиксы 2024–2025 лежат только в `develop`. Наша ниша — **картоводы и большие SDXC-карты** (дроны/камеры), где killer-фича — нативный быстрый exFAT, которого в ядре нет.

## 1. Состояние (сверено по клону)

| Параметр | Значение |
|---|---|
| Ветка / HEAD | `develop` / `52167ed` (2025-06-12) |
| Последний релиз | **0.10.0 (2023-03)** — фиксы из `develop` НЕ опубликованы |
| Сборка | AGP **8.7.2**, Gradle **8.9**, Kotlin **2.0.20**, NDK **25.2.9519653**, JVM target 21 |
| compileSdk / targetSdk | **33** (в `javafs` — 34); minSdk 15 |
| Лицензия | Apache-2.0 (форк-дружелюбная) |
| Размер | ~73 Kotlin + 20 Java; ядро невелико |

### Критичные поправки к первоначальным предположениям
- ✅ **16KB page size (issue #435) — УЖЕ СДЕЛАНО** в `develop`: `-Wl,-z,max-page-size=16384` стоит на всех 4 нативных либах (`libusb`, `libusbcom`, `usb-lib`, `errno-lib`). Это не задача — это **повод выпустить релиз**, потому что в опубликованной 0.10.0 фикса нет.
- ✅ Сборочный тулинг уже современный (не нужно «тащить с SDK 27 на 35», как у felHR85). Реальная работа — `targetSdk 33 → 35`.
- ✅ SCSI-фиксы (sense buffer overflow `280e101`, recoverable unit-attention `6535dab`) уже в `develop`, но не в релизе.
- ⚠️ `libusbcommunication` требует `local.properties` с `libusb.dir` → не собирается «из коробки». **Ядро (`core`) от libusb НЕ зависит** (свои `errno.c`/`usb.c`), собирается без него — это важно для релиза.
- ⚠️ Смешанные пакеты: ядро `me.jahnen.libaums.core.*`, но GPT всё ещё в легаси `com.github.mjdev.libaums.partition.gpt`.
- ⚠️ README ссылается на мёртвую инфру: Travis CI (`travis-ci.org` закрыт), Gitter.

## 2. Приоритеты

### P0 — Брендинг форка + вернуть пригодность к использованию (выходные)
1. **README: первой строкой — ссылка на Boosty** (донат-ссылка, без freemium/sponsor-perks — только ссылка). Дальше — пометка «fork of magnusja/libaums», новое имя/координаты.
2. **Перебрендировать координаты публикации.** Сейчас `me.jahnen.libaums:core:0.10.0`. Путь наименьшего сопротивления для форка — **JitPack**: тег → `com.github.<user>.libaums:core:<tag>`. Это сразу решает «артефакт 2023 года не резолвится».
3. **Выпустить релиз с уже накопленными фиксами** `develop` (SCSI sense overflow, recoverable unit-attention, 16KB). Это **самый дешёвый и самый ценный шаг** — экосистема застряла на 0.10.0.
4. Гигиена: GitHub Actions вместо Travis, убрать мёртвые бейджи, поднять `targetSdk/compileSdk 33 → 35`, выровнять `androidx.core:core-ktx` (1.9.0 vs 1.7.0 между модулями).

### P1 — Надёжность и совместимость (самые больные открытые issue)
Здесь сидит основная масса жалоб. Два треда-чемпиона:
5. **🔥 Надёжность записи** — #237 «result == -1, errno 0» (**63💬**, флагманская боль), плюс #21, #253, #275, #416, #394, #136, #390 (серия копирований). Симптом — bulk-transfer падает в -1 без вменяемого errno. Подбираем неслитую ветку `origin/bugfix/stall-result--1` + `origin/feature/trace` (трассировка для диагностики). Честно: частично device/firmware-specific, нужны реальные устройства + логи; начать с трассировки и `rich-exceptions`.
6. **🔥 Данные не сбрасываются перед извлечением** — #33 (**40💬**): создал папку/файл → unplug/plug → пропало. Это отсутствие flush/sync FAT-метаданных (FAT-таблицы + FsInfo + dir-entry) на диск. Также #336 (LOST.DIR), #338 («invalid fs info structure»). Аудит `fs/fat32/{FAT,FsInfoStructure,FatDirectory,ClusterChain}.kt` на предмет принудительного сброса.
7. **Стабильность init/transfer** — #384 (краш в init, 7💬), #415 (init нестабилен/таймаут), #419/#400/#392 (init throws), #428 (libusb -7 на Android 13), #110 (init висит минутами). В `ScsiBlockDevice` уже есть retry (`MAX_RECOVERY_ATTEMPTS=5`); заменить фикс. `Thread.sleep(100)` на экспоненциальный backoff, настраиваемые таймауты. Ветки `origin/less-recovery-attempts`, `origin/fix/298`.
8. **Совместимость с устройствами/вендорами** — #293 (OneUI/Android 11, 18💬), #430 (Android TV 11: `getPartitions` = 0), #347 (`ByteBuffer.position(I)` `NoSuchMethodError` — известный косяк ковариантного override Java 8/9, лечится компиляцией под нужный API), #283 (Unit attention 40/00).
9. **Android 14 SAF** в `storageprovider` — довести **PR #425**, починить #424 (краш на high API) и #259 (`mode "w"` возвращает null). Модуль помечен `@TargetApi(KITKAT)`, `targetSdk 33`.
10. **Баги корректности FAT32** — #413 (неверный размер файла), #410 (баг в `requestSense()`), #409 (не читается контент), #391 (jpg сохраняется, но не открывается через `BufferedOutputStream`), #389 (broken pipe на видео/архивах), #159 (FAT16: нельзя создать папку в корне).

### P2 — Стратегический дифференциатор: нативный exFAT (наша killer-фича)
8. **Нативный read/write exFAT в ядре.** Закрывает #418 (медленные exFAT/NTFS через jnode), #423 (запись на exFAT не работает), исторические #212/#302/#8. exFAT — дефолт для SDXC >32 ГБ (карты дронов/камер) → прямое попадание в нишу картоводов DJI.

   **Точки интеграции (подтверждены графом):**
   - Новый пакет `libaums/src/main/java/me/jahnen/libaums/core/fs/exfat/` по образцу `fs/fat32/`.
   - Реализовать `FileSystem` (`fs/FileSystem.kt`) + `UsbFile`-дерево (`fs/AbstractUsbFile.kt`) + `FileSystemCreator` (`fs/FileSystemCreator.kt`).
   - Зарегистрировать `ExFatFileSystemCreator` в `FileSystemFactory.init{}` (сейчас там только `Fat32FileSystemCreator` с приоритетом 1) — с приоритетом **выше** (меньше число), чем javafs-обёртка, чтобы перехватывать раньше.
   - Цепочка монтирования: `UsbMassStorageDevice.setupDevice` → `BlockDeviceDriverFactory.createBlockDevice` → `PartitionTableFactory.createPartitionTable` → `Partition.createPartition` → `FileSystemFactory.createFileSystem`. Для неразмеченных карт также научить `partition/fs/FileSystemPartitionTableCreator` распознавать exFAT VBR.

   **Сложность (честно): высокая.** Нужны: парс VBR exFAT, **allocation bitmap**, **up-case table**, FAT-цепочка vs `NoFatChain` (contiguous), 64-битные размеры, directory entry sets с set-checksum, имена в UTF-16. Это самый большой кусок плана — выносить в отдельную ветку, начинать с read-only, потом запись.
9. **Производительность** — #401/#406. `ScsiBlockDevice.read/write` шлёт по одной SCSI-команде на вызов; выигрыш — батчить несколько блоков и подсказывать `chunkSize`, уменьшая число round-trip'ов.

### P3 — Современный API (отличие от апстрима)
10. **Kotlin coroutine/`Flow` фасад** поверх блокирующих `UsbFileInputStream`/`UsbFileOutputStream`.
11. **Надёжность мульти-LUN картоводов.** `UsbMassStorageDevice.setupDevice` уже делает GET MAX LUN и итерирует `0..maxLun`, пропуская `MediaNotInserted`. Доработать: hot-swap слотов, surfacing per-LUN identity (важно для много-слотовых картоводов). Заодно — TODO в коде: `inited` не используется / разрешать только один `init()`.

## 3. Стратегия форка
- **Fork-and-own** (релиз на свой GitHub) — как решено. Boosty-ссылка первой строкой README.
- **Двойной трек по желанию:** не-брендинговые фиксы (релизная гигиена, exFAT, SAF) имеет смысл параллельно слать PR-ами в апстрим — апстрим иногда мёржит (внешние SCSI-фиксы залетели в `develop` в апреле 2025). Видимость + хорошая воля. Но это опционально, наш релиз самодостаточен.
- **Что НЕ делать:** не выдумывать freemium/sponsor-perks/двухуровневую модель. Ссылка на Boosty в README — и всё.

## 4. Порядок исполнения
1. P0 (брендинг + JitPack-релиз `develop`) → у форка сразу есть ценность и свежий артефакт.
2. P1.5 + P1.7 (стабильность SCSI, баги FAT32) → закрыть «не работает на моём устройстве».
3. P2.8 (exFAT, read-only → запись) — главная фича, отдельная ветка.
4. P3 (coroutine API, мульти-LUN) — дифференциаторы.

## 5. Риски
- **exFAT-запись** нетривиальна (bitmap/up-case/checksums) — высокий риск багов порчи ФС; обязательно тесты на образах + на реальных SDXC-картах, начинать с read-only.
- **Нет железа под все кейсы** — нужны реальные картоводы/карты разных вендоров для проверки мульти-LUN и SCSI-recovery.
- **libusbcommunication** не собирается без `libusb.dir` — задокументировать или вендорить libusb как submodule; для релиза `core` это не блокер.

---

## 6. Полный бэклог открытых issue (62 шт., сверено 2026-05-31)

### 6.1 Надёжность записи / передачи (🔥 главный кластер боли)
| Issue | 💬 | Суть |
|---|---|---|
| #237 | 63 | Could not write to device, result == -1, errno 0 — флагманский тред |
| #33 | 40 | Папка создаётся, но после unplug/plug пропадает (нет flush/sync) |
| #21 | 12 | Writing to ScsiBlockDevice failed |
| #253 | 11 | Error transferring command; errno 0 |
| #124 | 10 | Wrong CSW status |
| #416 / #394 / #275 / #136 | — | Could not read/write, result == -1 |
| #390 | 2 | Ошибка при последовательном копировании нескольких файлов |
| #389 | 0 | Broken pipe при открытии видео/архива |
| #391 | 0 | jpg сохранён, но не открывается (BufferedOutputStream) |
| #336 / #338 | 0 | LOST.DIR ломает USB / «invalid fs info structure» |

### 6.2 Init / стабильность подключения
#384 (краш в init, 7💬) · #415 (init нестабилен, таймаут) · #419 · #400 · #392 · #203 · #254 · #110 (init висит минутами) · #428 (libusb -7, Android 13) · #283 (Unit attention 40/00) · #347 (`ByteBuffer.position(I)` NoSuchMethodError).

### 6.3 Совместимость устройств / вендоров / Android-версий
#293 (Samsung OneUI 3.0 / Android 11, 18💬) · #430 (Android TV 11: getPartitions=0) · #156 (телефон↔телефон по OTG) · #258 (libusb_init -1, permission denied на non-root) · #61 (Conflicting Provider authority).

### 6.4 Корректность FAT
#413 (размер файла неверный) · #410 (баг в requestSense) · #409 (не читается контент) · #159 (FAT16: нет createDirectory в корне) · #388 (createDirectory IllegalArgumentException).

### 6.5 Производительность
#255 (медленное удаление многих файлов, label core/performance) · #401 (низкая скорость копирования) · #406 (16k-пакет дробится) · #182 (медленное закрытие IO при многих файлах) · #53 (исчерпать max transfer length) · #320 (multithreading).

### 6.6 storageprovider / SAF / API
#424 (краш на high API) · #259 (mode "w" → null, label bug/storageprovider) · #99 (content could not be loaded) · #357 (timestamp при копировании, label enhancement/API) · #287 (использовать ContentResolver) · #267 (NanoHTTPD).

### 6.7 Сборка / публикация / housekeeping
#435 (16KB — **уже в develop**, нужен релиз) · #370 (Maven publishing, AGP 8.0 software components) · #393 (build error с core:0.10.0) · #422 (включить libusb-файлы в проект) · #411 (инфраструктура тестов SCSI, label enhancement).

## 7. Что люди просили реализовать (feature requests)

| # | Запрос | Статус / польза для нас |
|---|---|---|
| #212/#302/#8 | **Нативный exFAT / NTFS** | Закрыты как wontfix, но 18+6+2💬. Открытые наследники #418 (медленно через javafs) и #423 (запись на exFAT не работает). **Наша killer-фича P2.** |
| #319 | **SDXC support** | exFAT по сути; то же направление |
| #294 | **SCSI 16-байтные команды (READ16/WRITE16)** | label enhancement/core. Сейчас только READ10/WRITE10 (`ScsiRead10`/`ScsiWrite10`) → лимит адресации (~2 ТБ). Нужно для больших дисков |
| #165 | **SFF-8070i** command set | label enhancement. ATAPI/floppy-устройства |
| #222 | **PTP-драйвер** для зеркалок/камер | label enhancement. Расширение за пределы Mass Storage |
| #301/#365 | **CD-ROM / virtual CD-ROM** | Многие флешки экспонируют фейковый CD-ROM LUN → init падает. Поддержать read-only этот LUN |
| #357 | Сохранять **timestamp** файла при копировании | label enhancement/API |
| #414 | Воспроизведение с флешки (usage) | пример/доки |
| — | **Kotlin coroutine/`Flow` API** | наша идея, нет issue — дифференциатор |

## 8. Открытые PR и неслитые ветки — «бесплатная» работа для подбора

**Открытые PR (3):**
- **#425** (2024-09, mikko-n) — Android 14 / SDK 34 в `UsbDocumentProvider` → довести и влить (P1.9).
- #375 (magnusja) — «Fix short name?» (ShortNameGenerator FAT32) → проверить, влить.
- #286 — файл констант для gradle-зависимостей → косметика.

**Неслитые ветки мейнтейнера (золото — уже написанный код):**
- `bugfix/stall-result--1` → прямо про #237 (stall на result == -1).
- `feature/trace` → трассировка USB-транзакций для диагностики #237-класса.
- `rich-exceptions` → информативные исключения вместо «errno 0 null».
- `less-recovery-attempts`, `fix/298` → тюнинг recovery / конкретный фикс.
- `ci-instrumented-tests*`, `reqsense/ci-instrum-tests` → инструментальные тесты (#411).
- `gradle-8-9`, `rm-bintray-gradle-plugin` → build housekeeping.

Первым делом стоит вычитать диффы этих веток: часть работы по P1 уже сделана и просто не дошла до `develop`.

## 9. Дорожная карта «добить всё» (укрупнённо)
1. **P0** — брендинг (Boosty), JitPack-релиз `develop`, GitHub Actions, `targetSdk→35`, влить #425/#375.
2. **P1 волна А (надёжность):** подобрать ветки `stall-result--1` + `feature/trace` + `rich-exceptions` → атаковать #237 и init-кластер с нормальной диагностикой.
3. **P1 волна Б (корректность):** flush/sync на unplug (#33/#336), баги FAT (#413/#410/#159), совместимость (#293/#430/#347).
4. **P2** — нативный exFAT (read-only → запись): #418/#423/#319/#212, + READ16/WRITE16 (#294) для больших носителей.
5. **P3** — производительность (#401/#406/#255/#53), coroutine/Flow API, мульти-LUN, опционально CD-ROM/PTP/SFF-8070i (#301/#222/#165).
