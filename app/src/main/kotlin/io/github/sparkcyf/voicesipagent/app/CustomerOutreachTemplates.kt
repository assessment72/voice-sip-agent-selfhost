package io.github.sparkcyf.voicesipagent.app

/** Editable, customer-specific outreach copy. WhatsApp names must match Meta-approved templates. */
data class CustomerOutreachTemplate(
    val stage: Int,
    val title: String,
    val emailSubject: String,
    val emailBody: String,
    val whatsappTemplateName: String = "",
    val whatsappLanguage: String = "ar",
)

object CustomerOutreachTemplates {
    const val COUNT = 10

    val defaults: List<CustomerOutreachTemplate> = listOf(
        CustomerOutreachTemplate(1, "التواصل الأول", "شكراً لتواصلكم يا {name}", "السلام عليكم {name}،\n\nنشكر لكم وقتكم واهتمامكم. يسعدنا التعرف إلى احتياجات {company} ومناقشة ما يمكن أن نقدمه. هل يناسبكم تحديد موعد قصير للمكالمة؟\n\nمع خالص التحية،\nفريق العمل"),
        CustomerOutreachTemplate(2, "تعريف مختصر", "نبذة موجزة عن خدماتنا", "السلام عليكم {name}،\n\nأشارككم نبذة مختصرة عن خدماتنا، ويسعدني توضيح التفاصيل المرتبطة باحتياج {company}. أخبروني بالوقت المناسب، أو أرسلوا أي سؤال تريدون البدء به.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(3, "فهم الاحتياج", "ما الأولوية الحالية لدى {company}؟", "السلام عليكم {name}،\n\nلأقترح عليكم خياراً مناسباً، ما الأولوية الأهم لديكم حالياً: السعر، سرعة التوريد، أم انتظام الخدمة؟ تكفينا إجابة مختصرة لنوجّه المتابعة بما يناسبكم.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(4, "عرض معلومات", "معلومات تساعدكم على التقييم", "السلام عليكم {name}،\n\nأستطيع تزويدكم بالمعلومات التي تساعد فريق {company} على التقييم، مثل نطاق الخدمة وآلية العمل وخطوات البدء. أخبروني بالنقاط التي تهمكم وسأرسلها مرتبة.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(5, "طلب موعد", "هل نحدد وقتاً مناسباً للمكالمة؟", "السلام عليكم {name}،\n\nإذا كان الموضوع مناسباً لكم، أقترح مكالمة قصيرة لمراجعة احتياج {company} والإجابة عن أسئلتكم. ما اليوم والوقت الأنسب لكم؟\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(6, "توضيح الاعتراضات", "هل توجد نقطة تحتاج إلى توضيح؟", "السلام عليكم {name}،\n\nأتابع معكم للتأكد من أن الصورة واضحة. إذا كانت هناك نقطة تحتاج إلى توضيح أو معلومة إضافية قبل اتخاذ القرار، يسعدني الإجابة عنها دون أي التزام.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(7, "متابعة هادئة", "متابعة بخصوص تعاون محتمل", "السلام عليكم {name}،\n\nأعود إليكم بمتابعة مختصرة حول إمكانية التعاون مع {company}. إذا كان التوقيت غير مناسب الآن، يمكنكم اقتراح موعد لاحق أو إبلاغي بذلك وسأوقف المتابعة.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(8, "خطوة تالية", "ما الخطوة التالية التي تفضلونها؟", "السلام عليكم {name}،\n\nلتسهيل المتابعة، يمكننا ترتيب مكالمة قصيرة، أو إرسال عرض مكتوب، أو تأجيل التواصل إلى وقت تختارونه. ما الخيار الأنسب لكم؟\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(9, "آخر متابعة قبل الإيقاف", "هل نتابع لاحقاً أم نغلق الطلب؟", "السلام عليكم {name}،\n\nهذه آخر متابعة مني حالياً حتى لا أكرر الرسائل. إذا رغبتم في استكمال الحديث عن احتياج {company}، يسعدني تلقي ردكم. وإن لم يكن مناسباً، فلا حاجة إلى الرد وسأغلق المتابعة.\n\nمع التحية،\nفريق العمل"),
        CustomerOutreachTemplate(10, "إغلاق مهذب", "شكراً لكم، وسنكون متاحين عند الحاجة", "السلام عليكم {name}،\n\nشكراً لكم على وقتكم. سأوقف رسائل المتابعة بعد هذه الرسالة، ويسعدنا استقبالكم إذا ظهر احتياج في المستقبل. نتمنى لكم ولفريق {company} دوام التوفيق.\n\nمع خالص التحية،\nفريق العمل"),
    )

    fun render(value: String, name: String, company: String): String = value
        .replace("{name}", name.trim().ifBlank { "عميلنا الكريم" })
        .replace("{company}", company.trim().ifBlank { "مؤسستكم" })

    fun safeSubject(value: String, name: String, company: String): String =
        render(value, name, company).replace(Regex("[\\r\\n]+"), " ").trim().take(180)
}
