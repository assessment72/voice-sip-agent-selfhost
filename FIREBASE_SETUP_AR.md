# إعداد Firebase وCloud Firestore

استبدل هذا الفرع اتصال MongoDB المباشر بتخزين Cloud Firestore من داخل تطبيق Android. لا يحتاج التطبيق إلى `google-services.json` وقت البناء؛ يمكن استيراد الملف من شاشة الإعدادات أو إدخال القيم يدويًا.

## 1. إنشاء المشروع

1. أنشئ مشروعًا في [Firebase Console](https://console.firebase.google.com/).
2. فعّل **Cloud Firestore** واختر المنطقة المناسبة.
3. أضف تطبيق Android، ثم احتفظ بالقيم التالية من `google-services.json`:
   - `project_info.project_id`
   - `client.client_info.mobilesdk_app_id`
   - `client.api_key[0].current_key`
4. من شاشة التطبيق اختر **استيراد google-services.json**، أو أدخل القيم الثلاث يدويًا.
5. اضغط **اختبار الاتصال** ثم **حفظ وتفعيل**.

يُخزّن Project ID وApplication ID في SharedPreferences، بينما يُخزّن Firebase API key باستخدام Android Keystore. لا يسجل التطبيق أيًا من هذه القيم في السجل.

## 2. قواعد Firestore

تحتاج قواعد Firestore إلى السماح بالوصول للتطبيق. المثال التالي مناسب للاختبار الأولي فقط، ولا يُنصح به للإنتاج لأنه يسمح بالقراءة والكتابة لأي عميل يملك إعدادات المشروع:

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if true;
    }
  }
}
```

للإنتاج، فعّل Firebase Authentication (يفضل Anonymous أو حساب خدمة عبر جسر خادم) واستخدم قواعد مقيدة حسب `request.auth.uid`. لا تضع مفتاح Service Account داخل APK.

## 3. المجموعات المستخدمة

يستخدم التطبيق المجموعات التالية: `customers`, `customer_memories`, `call_summaries`, `call_schedules`, `sms_schedules`, `campaigns`, `campaign_customers`, `interested_customers`، إضافة إلى `_voicx_health/ping` لاختبار الاتصال.

المعرّفات نصية مستقرة بدل Mongo ObjectId. تُخزّن أوقات التشغيل كأرقام Unix milliseconds، وتتم عملية التقاط الاتصالات والرسائل المجدولة داخل Firestore transaction لمنع التنفيذ المكرر عند وجود أكثر من عامل.

## 4. الفهارس

تبدأ النسخة الحالية باستعلامات لا تتطلب فهارس مركبة قدر الإمكان. إذا أظهر Firestore رابطًا لإنشاء فهرس عند تشغيل حملات أو قوائم مجدولة، افتح الرابط وأنشئ الفهرس المقترح من Firebase Console.

## 5. ملاحظة أمنية

Firebase API key ليس سرًا بحد ذاته، لكن قواعد Firestore هي طبقة الحماية الأساسية. لا تستخدم قواعد `allow read, write: if true` في الإنتاج، ولا تضع مفاتيح Gemini أو SMS أو Service Account في مستودع Git.
