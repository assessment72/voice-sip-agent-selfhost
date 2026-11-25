# تدقيق مصدر الصوت وموضع دمج Local AI

**المستودع:** assessment72/voice-sip-agent-selfhost
**الفرع:** feat/realtime-ai-voice-integration-plan

## البنية المتحققة

- :voice-api: عقود CallEndpoint وCallLeg وMediaPort وPcmFormat.
- :sip: SipUserAgent وRtpSession وG711 PCMU/PCMA وJitterBuffer.
- :gateway: CallBridge وMediaBridge.
- :telephony و:privileged: مسار المكالمات الخلوية وShizuku؛ خارج نطاق ميزة SIP-only.
- :app: Android Compose وتهيئة runtime.
- التطبيق يستهدف arm64-v8a، Java 17، minSdk 30 وtargetSdk 36.

## مسار SIP الصوتي المؤكد من المصدر

- RtpSession يفك G.711 إلى ShortArray PCM.
- MediaPort الافتراضي هو PCM16 mono بمعدل 8 kHz؛ الإطار 20 ms.
- MediaBridge يقرأ من كل منفذ ويكتب الإطار إلى المنفذ المقابل.
- لذلك نقطة الدمج المناسبة هي MediaPort/CallEndpoint في :gateway، وليس التقاط صوت Android أو Shizuku أو تعديل G.711/RTP.

## ملفات تمت مراجعتها

- sip/src/main/kotlin/io/github/sparkcyf/voicesipagent/sip/media/RtpSession.kt
- sip/src/main/kotlin/io/github/sparkcyf/voicesipagent/sip/media/G711.kt
- voice-api/src/main/kotlin/io/github/sparkcyf/voicesipagent/voice/MediaPort.kt
- voice-api/src/main/kotlin/io/github/sparkcyf/voicesipagent/voice/PcmFormat.kt
- voice-api/src/main/kotlin/io/github/sparkcyf/voicesipagent/voice/CallEndpoint.kt
- voice-api/src/main/kotlin/io/github/sparkcyf/voicesipagent/voice/CallLeg.kt
- voice-api/src/main/kotlin/io/github/sparkcyf/voicesipagent/voice/AbstractCallLeg.kt
- gateway/src/main/kotlin/io/github/sparkcyf/voicesipagent/gateway/MediaBridge.kt
- gateway/src/main/kotlin/io/github/sparkcyf/voicesipagent/gateway/CallBridge.kt

## طبقة التنفيذ المضافة

- gateway/.../LocalAiMediaPort.kt: واجهة PCM متزامنة مع ساعة الجسر، دون inference داخل callback.
- gateway/.../LocalAiEndpoint.kt: طرف محلي يمكن أن يستقبله CallBridge كمكالمات SIP واردة ثم ينشئ محركاً مستقلاً لكل مكالمة.
- اختبارات LocalAiMediaPortTest وLocalAiEndpointTest.

## القيود المفتوحة

- LocalSpeechEngine عقد فقط؛ لا يوجد native backend بعد.
- لم يتم تضمين whisper.cpp أو llama.cpp أو VAD أو TTS.
- لم يتم توصيل LocalAiEndpoint في GatewayRuntime أو UI؛ لذلك الميزة غير مفعلة وغير جاهزة للمستخدم.
- لم يتم تشغيل Gradle/CI بعد هذه التغييرات، ولم يتم إجراء اختبار SIP حي على HONOR MTN-NX1.
- لا يثبت هذا التدقيق بعدُ استهلاك الذاكرة أو الأداء الحراري أو دعم جلسات متعددة.

## الخطوة التالية

إضافة backend محلي قابل للبناء على Android ARM64، واختبار native lifecycle والذاكرة، ثم توصيل إعداد opt-in إلى مسار SIP فقط بعد نجاح اختبارات الوحدة.
