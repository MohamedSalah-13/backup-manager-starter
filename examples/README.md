# أمثلة عملية

مشروعان يعملان فوراً بدون أي إعداد خارجي — لا قاعدة بيانات تُنصَّب، ولا حساب سحابي.
كلاهما ينشئ بياناته عند أول تشغيل.

---

## 1. تطبيق سطح مكتب — JavaFX

قاعدة SQLite + مجلد مستندات، مع نسخ إلى وجهتين، تشفير، جدولة، والشاشات الجاهزة.

ثبّت المكتبة مرة واحدة من جذر المشروع:

```bash
mvn install -DskipTests
```

ثم شغّل التطبيق **من داخل مجلد الموديول**:

```bash
cd examples/desktop-javafx && mvn javafx:run
```

كل شيء يُكتب تحت `examples/desktop-javafx/demo-data/` — احذف المجلد لتصفير المثال.

**جرّب هذا:** لاحظ عدد العملاء في الشريط العلوي ← اضغط "Back up now" في تبويب History ←
أضف عملاء ← استعِد النسخة ← اضغط Refresh. العدد يرجع كما كان.

الملفات:

| الملف | الغرض |
|---|---|
| `DemoBackupSetup.java` | **كل إعداد النسخ**، منفصل عن الواجهة عمداً |
| `DesktopExampleApp.java` | الواجهة فقط: ثلاثة تبويبات جاهزة + شريط تجريبي |
| `DemoDatabase.java` | قاعدة SQLite تجريبية بـ 500 صف |

فصل الإعداد عن `Application` يستحق النقل لمشروعك: إعدادات النسخ الاحتياطي بالضبط ما تريد
اختباره، وأي كود داخل `Application.init()` لا يمكن تشغيله إلا بفتح نافذة حقيقية.
لهذا يوجد `DemoBackupSetupTest` — يشغّل نفس الإعداد بلا شاشة.

---

## 2. خدمة Spring Boot

قاعدة H2 تنسخ نفسها، مضبوطة **بالكامل** من `application.yml` — لا يوجد كود إعداد Java إطلاقاً.

```bash
cd examples/spring-boot-service && mvn spring-boot:run
```

ثم:

```bash
curl -X POST http://localhost:8080/api/backups/invoices-db/run
```

```bash
curl http://localhost:8080/actuator/health
```

### نقاط النهاية

| الطريقة | المسار | الغرض |
|---|---|---|
| `GET` | `/api/backups` | حالة كل مهمة |
| `GET` | `/api/backups/{job}` | سجل المهمة |
| `POST` | `/api/backups/{job}/run` | تشغيل نسخة الآن |
| `POST` | `/api/backups/records/{id}/verify` | قراءة النسخة وفك تشفيرها بلا كتابة |
| `POST` | `/api/backups/records/{id}/restore?confirm=true` | استعادة فوق البيانات الحية |
| `GET` | `/actuator/health` | يصبح `DOWN` عند الفشل **أو** التقادم |
| `GET` | `/actuator/backup` | نقطة نهاية الإدارة |

الاستعادة تتطلب `confirm=true` صراحةً. نقطة نهاية النسخ الاحتياطي هي نقطة تدمير بيانات
موجّهة في الاتجاه المعاكس — في خدمة حقيقية ضعها خلف دور إداري ومنفذ إدارة محمي.

### دورة كاملة مُختبَرة

```bash
REC=$(curl -s http://localhost:8080/api/backups/invoices-db | python -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
```

```bash
curl -X POST "http://localhost:8080/api/backups/records/$REC/restore?confirm=true"
```

### ما يوضّحه `application.yml`

- مهمتان بمصادر مختلفة (H2 وملفات) في ملف واحد
- **وجهتان** لقاعدة البيانات — الفرق بين نسخة احتياطية ونقطة فشل وحيدة
- تشفير من متغيّر بيئة: `BACKUP_PASSWORD`؛ اتركه فارغاً فتُكتب النسخ بلا تشفير
- منطقة زمنية مثبَّتة على الـ cron، فنقل الخادم لا يزيح الموعد
- `stale-after: 25h` — الصحة تصبح `DOWN` إذا توقّفت النسخ بصمت، لا عند الفشل فقط
- مهمة S3 بـ `enabled: false`: مكتوبة بالكامل وجاهزة، ولا تفرض تبعية AWS SDK إطلاقاً

---

---

## لماذا `cd` ولا يعمل `-pl` من الجذر

الشكل التالي **يفشل**، وليس خطأ مطبعياً:

```bash
mvn -pl examples/desktop-javafx -am javafx:run
```

```
[ERROR] No plugin found for prefix 'javafx' in the current project
```

لسببين مستقلّين:

1. **حلّ بادئة الـ plugin يتمّ مقابل المشروع الذي تقف فيه**، أي الـ POM الجذر — وهو لا يعرّف
   `javafx-maven-plugin`. البادئة `javafx` غير معروفة هناك. ينطبق نفس الكلام على
   `spring-boot:run`.

2. **`-am` يضيف كل التبعيات إلى الـ reactor** (الأب، النواة، موديول JavaFX، قواعد الملفات)،
   واستدعاء هدف مباشر يُنفَّذ على *كل* مشروع في الـ reactor. حتى لو حُلّت البادئة، لحاول
   Maven تشغيل التطبيق من داخل النواة أيضاً.

الدخول إلى مجلد الموديول يحلّ الاثنين: البادئة تُقرأ من POM الموديول الذي يعرّف الـ plugin،
والـ reactor يصبح مشروعاً واحداً. و`javafx:run` يستدعي `process-classes` تلقائياً، فلا حاجة
لترجمة يدوية — يكفي أن تكون موديولات المكتبة مثبّتة (`mvn install` مرة واحدة من الجذر).

---

## ملاحظة

هذان المثالان جزء من الـ reactor، فـ `mvn clean install` في الجذر يبنيهما ويشغّل اختباراتهما.
هذا مقصود: مثال لا يُبنى مع كل تغيير يتعفّن بصمت. لكنهما لا يُنشران أبداً
(`maven.deploy.skip` و`maven.install.skip`).
