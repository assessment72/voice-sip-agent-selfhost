# RTP_LOCAL_SERVER_AUDIT.md

## النطاق

تمت مراجعة الفرع `feat/network-monitor-24h` في المستودع `voice-sip-agent-selfhost` دون تعديل `main` أو دمج أو نشر أو إجراء مكالمات حقيقية.

المراجعة غطت:

- حجز منافذ RTP وإدارة دورة حياة الـ sockets.
- تحليل وبناء SDP وربط المنفذ المعلن بالمنفذ المحلي.
- عزل جلسات RTP والتحقق من مصدر الحزم.
- SIP registrar وإعادة التسجيل.
- Foreground Service وWakeLock وإعادة التشغيل بعد الإقلاع.
- مراقبة الشبكة والتعامل مع تغيّر الشبكة.
- جسر الصوت بين SIP وTelecom/GSM.
- اختبارات JVM الموجودة ومسار بناء Android.

## الحالة قبل التعديل

الفرع يحتوي بالفعل على أساس قوي للمتطلبات المطلوبة:

- SIP المحلي الافتراضي على UDP `5062` في وضع server mode.
- حجز RTP لكل مكالمة من النطاق `10000..20000`، مع منفذ منفصل لكل `SipCall`.
- إغلاق جلسة RTP والـ socket عند إنهاء المكالمة.
- SDP يعلن `ua.localIp` والمنفذ المحلي الفعلي للـ socket.
- دعم G.711 وRTP ثنائي الاتجاه وRFC 4733 DTMF.
- Foreground Service دائم مع إشعار و`PARTIAL_WAKE_LOCK`.
- إعادة تسجيل SIP عند تغيّر الشبكة، وإعادة محاولة التسجيل بعد الفشل.
- `BOOT_COMPLETED` و`MY_PACKAGE_REPLACED` لتشغيل الخدمة عند الإقلاع/التحديث.
- مراقبة محلية لحالة الإنترنت والـ transport وتجنب خدمة speed-test خارجية.
- حماية كلمات مرور SIP المخزنة باستخدام Android Keystore وAES-GCM.
- منع INVITE الخارجي من إجراء مكالمات GSM عند غياب كلمة مرور registrar.

## الإصلاحات المنفذة على الفرع

### 1. تشديد عزل RTP

في `sip/src/main/kotlin/io/github/sparkcyf/voicesipagent/sip/media/RtpSession.kt` كان التحقق الوارد يقارن عنوان IP فقط. لذلك كان يمكن لحزمة من منفذ مختلف على نفس العنوان أن تدخل إلى جلسة المكالمة وتؤثر على الصوت أو DTMF.

تمت إضافة تحقق من `pkt.port == p.remote.port` قبل قبول الحزمة. هذا يطابق المنفذ الذي أعلنه الطرف الآخر في SDP ويمنع حقن RTP بين الجلسات. القيد التشغيلي هو أن deployments التي تعتمد على NAT متناظر يجب أن تعلن/تحوّل نفس منفذ RTP؛ لا ينبغي اعتبار حجز المنفذ المحلي دليلاً على إمكانية الوصول من الإنترنت.

### 2. تحسين حجز منافذ RTP

تم استبدال المحاولة العشوائية المحدودة إلى 50 محاولة بقائمة منافذ زوجية ضمن النطاق، بترتيب عشوائي ومحاولة كل المنافذ الممكنة. كما أضيف التحقق من صحة النطاق قبل الحجز، مع فشل واضح عند عدم وجود منفذ زوجي صالح.

### 3. التحقق من منفذ SDP

تم تعديل `Sdp.parse` لرفض منفذ صوت أقل من `0` أو أكبر من `65535` بدلاً من قبول أي قيمة قابلة للتحويل إلى عدد صحيح.

### 4. اختبارات جديدة

أضيف:

- `sip/src/test/kotlin/io/github/sparkcyf/voicesipagent/sip/RtpSessionTest.kt`
  - يتأكد من أن المنفذ زوجي وداخل النطاق.
  - يتأكد من رفض النطاق المعكوس.
  - يتأكد من الإبلاغ عن نطاق لا يحتوي منفذاً زوجياً صالحاً.
- اختبار `sdpRejectsInvalidMediaPort` في `MediaTest.kt`.

## نتائج الاختبارات

تم تشغيل:

```bash
./gradlew test --no-daemon --stacktrace
```

النتيجة: **لم يبدأ الاختبار فعلياً** لأن بيئة التنفيذ لا تحتوي Android SDK:

```text
SDK location not found
ANDROID_HOME is not set
ANDROID_SDK_ROOT is not set
No local.properties with sdk.dir was present
```

لذلك لا يمكن اعتبار نتيجة Gradle دليلاً على نجاح أو فشل اختبارات Kotlin؛ الفشل بيئي قبل التجميع. يلزم تشغيل الاختبارات على جهاز/CI يحتوي Android SDK المتوافق مع المشروع، ثم تنفيذ:

```bash
./gradlew --no-daemon --no-configuration-cache testDebugUnitTest
./gradlew --no-daemon --no-configuration-cache :app:assembleDebug
```

كما تم تنفيذ فحص whitespace عبر `git diff --check` وكانت النتيجة نظيفة.

## ما لم يتم إثباته

لا يوجد في هذه البيئة هاتف Android فعلي أو SIM أو Shizuku أو شبكة خارجية قابلة لاختبار NAT. لذلك لم يتم إثبات:

- استقبال مكالمة بعد 24 ساعة.
- استقبال مكالمة والشاشة مطفأة أو الهاتف مقفل.
- استمرار الصوت بعد إعادة تشغيل الهاتف.
- نجاح Shizuku تلقائياً بعد reboot؛ الوثائق الحالية تشير إلى أنه يحتاج إعادة تشغيل على الأجهزة غير المروّتة.
- مكالمة GSM واردة أو صادرة حقيقية.
- صوت ثنائي الاتجاه على نموذج هاتف محدد.
- مكالمتان متزامنتان على جهاز فعلي.
- إمكانية الوصول إلى UDP `5062` وRTP `10000..20000` من الإنترنت.

## المخاطر والمهام المتبقية

1. **اختبار Android فعلي:** يجب تنفيذ مصفوفة الاختبارات على هاتف Android 13+ حقيقي، مع تمييز نتائج المحاكاة عن النتائج الفعلية.
2. **NAT والجدار الناري:** يجب توثيق هل الشبكة تسمح بالاتصالات الواردة، وهل يلزم public IP أو port forwarding أو VPN مثل Tailscale.
3. **اختبار مصدر RTP:** الإصلاح الحالي متعمد التشدد في مطابقة منفذ المصدر مع منفذ SDP. يجب اختبار ذلك مع softphone وشبكة VPN/NAT المستهدفة قبل اعتماده في بيئات تغيّر منفذ المصدر.
4. **إعادة التشغيل:** على جهاز غير مروّت، يجب التحقق عملياً من دورة Shizuku بعد reboot، وعدم الادعاء بأن الخدمة 24/7 بالكامل دون هذا الاختبار.
5. **نطاق المنتج:** outgoing SIP-to-cellular موجود ومغطى باختبار bridge، لكن README يذكر أن المكالمة الصادرة الحقيقية لم تُتحقق على هاتف فعلي بعد.
6. **السرية:** يجب إبقاء registrar password مضبوطاً قبل تعريض UDP للإنترنت، وتقييد `allowedPrefixes` لتقليل مخاطر toll fraud.

## الملفات المعدلة

- `sip/src/main/kotlin/io/github/sparkcyf/voicesipagent/sip/media/RtpSession.kt`
- `sip/src/main/kotlin/io/github/sparkcyf/voicesipagent/sip/media/Sdp.kt`
- `sip/src/test/kotlin/io/github/sparkcyf/voicesipagent/sip/MediaTest.kt`
- `sip/src/test/kotlin/io/github/sparkcyf/voicesipagent/sip/RtpSessionTest.kt`
- `RTP_LOCAL_SERVER_AUDIT.md`

## القيود التي تم احترامها

- العمل على `feat/network-monitor-24h` فقط.
- عدم تعديل `main` أو دمج التغييرات.
- عدم النشر.
- عدم إجراء مكالمات حقيقية.
- عدم تغيير إعدادات الإنتاج.
- عدم الادعاء بنجاح التشغيل 24 ساعة دون اختبار فعلي موثق.
