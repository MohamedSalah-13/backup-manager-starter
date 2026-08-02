# Backup Manager

مكتبة نسخ احتياطي قابلة للإضافة كـ plugin لمشاريع Java و JavaFX و Spring Boot.

تعمل النواة في أي تطبيق Java عادي بدون Spring، ومع Spring Boot تُضبط بالكامل من
`application.yml`. كل ما يخص النسخ الاحتياطي في مكان واحد: مصادر، ضغط، تشفير، جدولة،
سياسات احتفاظ، تحقق، استعادة، وواجهات JavaFX جاهزة.

---

## المحتويات

| الموديول | الغرض | التبعيات الثقيلة |
|---|---|---|
| `backup-manager-core` | المحرك، الأنابيب، التشفير، الجدولة، الاحتفاظ، التخزين المحلي | لا شيء (SLF4J + Jackson + commons-compress) |
| `backup-manager-db-mysql` | MySQL / MariaDB عبر `mysqldump` أو JDBC | اختيارية |
| `backup-manager-db-file` | SQLite / H2 / Derby | اختيارية |
| `backup-manager-storage-s3` | S3 وأي خدمة متوافقة (MinIO، R2، B2) | AWS SDK |
| `backup-manager-storage-gdrive` | Google Drive | Google API Client |
| `backup-manager-storage-dropbox` | Dropbox | Dropbox SDK |
| `backup-manager-spring-boot-starter` | ضبط تلقائي + `application.yml` + Actuator | Spring Boot |
| `backup-manager-javafx` | شاشات جاهزة | JavaFX (`provided`) |

خذ النواة فقط، وأضف ما تحتاجه. لا شيء يُفرض عليك.

---

## التثبيت

```xml
<dependency>
    <groupId>com.backupmanager</groupId>
    <artifactId>backup-manager-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

ثم أضف ما يلزم مشروعك:

```xml
<dependency>
    <groupId>com.backupmanager</groupId>
    <artifactId>backup-manager-db-mysql</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.backupmanager</groupId>
    <artifactId>backup-manager-javafx</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

للبناء محلياً:

```bash
mvn clean install
```

---

## الاستخدام في تطبيق Java / JavaFX عادي

```java
BackupManager manager = BackupManager.builder()
        .storageRoot(Path.of("C:/ProgramData/MyApp/backups"))
        .job(BackupJob.builder("main-db")
                .source(new MySqlBackupSource(
                        MySqlConfig.of("localhost", 3306, "sales", "backup_user", password)))
                .destination(new LocalStorageProvider("disk", Path.of("D:/backups")))
                .destination(LocalStorageProvider.networkShare("nas", "\\\\nas\\backups"))
                .compressor(new GzipCompressor())
                .encryptWithPassword(encryptionPassword)
                .dailyAt(2, 30)
                .retention(RetentionRule.gfs(7, 4, 12, 0))
                .build())
        .build();

manager.start();                          // يبدأ الجدولة
BackupRecord record = manager.backupNow("main-db");   // تشغيل فوري
manager.restore(record.getId());          // استعادة
```

أغلقه عند إنهاء التطبيق:

```java
manager.close();
```

---

## الاستخدام مع Spring Boot

أضف الـ starter، ثم اضبط كل شيء من `application.yml`:

```xml
<dependency>
    <groupId>com.backupmanager</groupId>
    <artifactId>backup-manager-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
backup:
  storage-root: ./backup-data
  auto-start: true

  jobs:
    main-db:
      source:
        type: mysql
        host: localhost
        database: sales
        username: backup_user
        password: ${DB_BACKUP_PASSWORD}
        exclude-tables: [audit_log, session_cache]

      destinations:
        - type: local
          path: D:/backups
        - type: local
          name: nas
          path: \\\\nas\\backups
        - type: s3
          bucket: company-backups
          region: eu-central-1
          storage-class: STANDARD_IA

      schedule:
        cron: "0 30 2 * * *"

      compression: gzip
      encryption:
        password: ${BACKUP_ENCRYPTION_PASSWORD}

      retention:
        keep-daily: 7
        keep-weekly: 4
        keep-monthly: 12
        minimum-kept: 3

    user-documents:
      source:
        type: files
        paths: [C:/MyApp/documents, C:/MyApp/templates]
        exclude: ["**/*.tmp", "**/cache/**"]
      destinations:
        - type: local
          path: D:/backups/docs
      schedule:
        fixed-rate: 6h
      retention:
        keep-last: 20
```

`BackupManager` يصبح bean عادي:

```java
@Service
public class ReportService {

    private final BackupManager backups;

    public ReportService(BackupManager backups) {
        this.backups = backups;
    }

    public void beforeRiskyMigration() {
        backups.backupNow("main-db");
    }
}
```

### Actuator

مع `spring-boot-actuator` على الـ classpath:

- `GET /actuator/health` → مدخل `backup` يصبح `DOWN` إذا فشل آخر تشغيل **أو** إذا مضى
  على آخر نسخة ناجحة أكثر من `backup.actuator.stale-after` (افتراضياً يومان)
- `GET /actuator/backup` → حالة كل مهمة
- `GET /actuator/backup/{job}` → سجل المهمة
- `POST /actuator/backup/{job}` → تشغيل نسخة الآن

> نقطة النهاية تُشغّل نسخاً احتياطية، فاعرضها على منفذ إدارة محمي فقط.
> الاستعادة غير متاحة عبر HTTP عمداً.

---

## واجهات JavaFX

```java
FxBackupService service = new FxBackupService(manager);

TabPane tabs = new TabPane(
        new Tab("نظرة عامة", new BackupDashboardPane(manager)),
        new Tab("السجل", new BackupHistoryPane(service)),
        new Tab("الجدولة", new ScheduleEditorPane(job.getSchedule())));
```

`FxBackupService` ينشر كل شيء عبر `Platform.runLater`، فتربط الخصائص مباشرة:

```java
progressBar.progressProperty().bind(service.progressProperty());
statusLabel.textProperty().bind(service.statusMessageProperty());
runButton.disableProperty().bind(service.runningProperty());
```

`ScheduleEditorPane` يعرض **المواعيد الخمسة القادمة فعلياً** أثناء التعديل — أسرع طريقة
لاكتشاف تعبير cron خاطئ قبل أن يكتشفه غياب النسخ بعد شهر.

---

## القرارات التصميمية المهمة

### التشفير: AES-256-GCM على شكل chunks

`CipherOutputStream` مع GCM غير مناسب للنسخ الاحتياطي: الوسم يُتحقق منه في النهاية فقط،
فملف بحجم عدة جيجابايت يجب تحميله كاملاً في الذاكرة أو الوثوق به أعمى — وبعض إصدارات JDK
تبتلع فشل الوسم تماماً.

هنا يُختم كل chunk بحجم 1 ميجابايت على حدة:
- استهلاك ذاكرة ثابت مهما كبر الملف
- التلف يُكتشف داخل chunk واحد
- علامة نهاية صريحة تكشف الملفات المبتورة (رفع فشل في منتصفه)
- الترويسة و رقم الـ chunk يدخلان في AAD → العبث بالترويسة أو إعادة ترتيب الـ chunks يُكتشف

اشتقاق المفتاح: PBKDF2-HMAC-SHA256 بـ 210,000 تكرار (توصية OWASP). عدد التكرارات يُخزَّن
في الملف، فرفع الافتراضي لاحقاً لا يُعطّل النسخ القديمة.

> **إذا ضاعت كلمة المرور فالنسخ غير قابلة للاسترجاع — بالتصميم.** خزّنها منفصلة عن النسخ.

### النسخ المرحلي قبل الرفع

الملف يُكتب محلياً أولاً ثم يُرفع. الأسباب:
- الـ checksum معروف قبل نشر أي شيء
- الوجهة الثانية تكلّف نسخة إضافية، لا عملية dump إضافية على قاعدة البيانات
- فشل الرفع يُعاد بدون لمس قاعدة البيانات مرة أخرى

### الوجهات المتعددة لا تُسقط بعضها

فشل وجهة واحدة يعطي الحالة `PARTIAL` مع الاحتفاظ بالنسخ الناجحة، لا `FAILED`.
لتغيير ذلك: `requireAllDestinations(true)`.

### التشغيلات الفاشلة لا تستهلك حصة الاحتفاظ

مع `keepLast=7`، أسبوع من الفشل لن يطرد آخر نسخة سليمة. وسياسات الاحتفاظ تعمل باتحاد ما
تريد **الإبقاء** عليه، فتفعيل قاعدة إضافية لا يمكن أن يحذف أكثر — فقط أقل.

### التحقق بعد التخزين

مفعّل افتراضياً: يعيد تنزيل كل نسخة مخزّنة ويقارن الـ checksum. هذا هو الفرق بين
"استدعاء الرفع رجع بنجاح" و"البايتات موجودة فعلاً وصحيحة". يكلّف تنزيلاً كاملاً لكل وجهة —
عطّله بـ `verifyAfterStore(false)` للملفات الضخمة على وصلات محدودة.

### MySQL بدون أدوات مثبتة

`Strategy.AUTO` يبحث عن `mysqldump`، وإن لم يجده يولّد نفس الـ dump عبر JDBC خالص.
هذا ما يجعل المكتبة تعمل على جهاز عميل مثبَّت من MSI بدون أدوات MySQL.

كلمات المرور لا تُمرَّر أبداً كوسيط سطر أوامر (مرئية في قائمة العمليات لكل مستخدم) —
بل عبر ملف خيارات بصلاحيات المالك فقط.

### SQLite: لا تنسخ الملف مباشرة

نسخ ملف `.db` أثناء الكتابة يعطي نسخة غير متسقة، ومع WAL فإن آخر التغييرات في ملف منفصل
يفوته النسخ الساذج. المكتبة تستخدم `VACUUM INTO` الذي يكتب نسخة متسقة ومكتملة
بينما تبقى القاعدة مفتوحة للقراءة.

---

## أنواع النسخ

| النوع | المحتوى | الاستعادة |
|---|---|---|
| `FULL` | كل شيء | مستقلة |
| `INCREMENTAL` | ما تغيّر منذ آخر نسخة | تُعيد تشغيل السلسلة كاملة |
| `DIFFERENTIAL` | ما تغيّر منذ آخر `FULL` | تحتاج الـ FULL + هذه |

مصادر قواعد البيانات تدعم `FULL` فقط (الـ dump المنطقي دائماً لقطة كاملة).
مصدر الملفات يدعم الثلاثة. إذا طُلبت نسخة تزايدية ولا توجد نسخة أساس، تُرقّى تلقائياً إلى `FULL`.

---

## سياسات الاحتفاظ

```java
RetentionRule.keepLast(10)
RetentionRule.maxAgeDays(90)
RetentionRule.gfs(7, 4, 12, 3)   // 7 يومية، 4 أسبوعية، 12 شهرية، 3 سنوية
RetentionRule.keepLast(5).setMaxAge(Duration.ofDays(30)).setMinimumKept(3)
```

`minimumKept` أرضية تُطبَّق أخيراً — لا يمكن لأي قاعدة أخرى النزول تحتها.

---

## التوسّع

كل شيء خلف واجهة. لإضافة وجهة تخزين جديدة (FTP، SFTP، Azure Blob):

```java
public class SftpStorageProvider implements StorageProvider {
    public String name() { return "sftp"; }
    public String store(Path localFile, String remotePath, ProgressCallback progress) { ... }
    public void fetch(String remotePath, Path localTarget, ProgressCallback progress) { ... }
    public boolean exists(String remotePath) { ... }
    public void delete(String remotePath) { ... }
    public List<RemoteEntry> list(String prefix) { ... }
}
```

ثم `.destination(new SftpStorageProvider(...))`. المحرك لا يحتاج أي تعديل.

لإضافة مصدر جديد (PostgreSQL، MongoDB) نفّذ `BackupSource` — الواجهة متماثلة عمداً:
`dump` تكتب الحمولة، و`restore` تقرأها. الضغط والتشفير والـ checksum والتوزيع
والاحتفاظ كلها مسؤولية المحرك.

لإضافة ضاغط: نفّذ `Compressor` وسجّله بـ `Compressors.register(...)` عند بدء التطبيق —
المعرّف المخزَّن في السجل هو ما يُستخدم عند الاستعادة، فالنسخ القديمة تبقى قابلة للقراءة.

---

## التغطية الاختبارية

111 اختباراً، منها:
- دورة تشفير كاملة على 5 ميجابايت، كلمة مرور خاطئة، عبث بالبايتات، عبث بالترويسة، ملف مبتور
- 18 اختبار cron يشمل النطاقات الملتفة، `L`، الانتقال الصيفي، أسماء الأيام والشهور
- نسخ واستعادة فعليان لقواعد SQLite و H2 حقيقية بصفوف حقيقية
- لقطة SQLite متسقة أثناء وجود معاملة غير مُثبتة مفتوحة
- تقطيع SQL مع `DELIMITER` وفواصل منقوطة داخل النصوص والتعليقات
- فشل وجهة واحدة، فشل كل الوجهات، سلاسل تزايدية، كشف التلف

```bash
mvn test
```

---

## ملاحظات تشغيلية

**غيّر الـ groupId** من `com.backupmanager` إلى نطاقك قبل النشر على مستودع داخلي.

**اختبر الاستعادة، لا النسخ فقط.** نسخة احتياطية لم يقرأها أحد ليست شبكة أمان بل فرضية.
`manager.testRestore(recordId, progress)` يقرأ الملف ويفك تشفيره كاملاً بدون كتابة أي شيء —
شغّله دورياً.

**مجلد العمل** (`storageRoot/work`) يحتاج مساحة تكفي أكبر ملف تنتجه أضخم مهمة، ويحتوي
نصاً غير مشفَّر أثناء التشغيل. يُنظَّف عند بدء التطبيق تحسباً لتعطّل سابق.

**المنطقة الزمنية:** ثبّت `zone` في الجدولة إذا كانت النسخ يجب أن تتوافق مع تقويم عمل
محدد بدل إعدادات الخادم.
