package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

internal data class AdminButton(val title: String, val callbackData: String)
internal data class AdminReply(val text: String, val buttons: List<List<AdminButton>> = emptyList())

internal data class CoverageEntry(val region: String, val confirmed: Boolean)
internal data class DieselPrice(
    val region: String,
    val amount: String,
    val currency: String,
    val unit: String,
    val validFrom: String,
    val validUntil: String,
)

/** Local configuration for manager-approved commercial facts; never treats expired prices as active. */
internal class SalesAdminStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sales_admin", Context.MODE_PRIVATE)

    @Synchronized fun coverage(): List<CoverageEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY_COVERAGE, "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let {
            CoverageEntry(it.optString("region"), it.optBoolean("confirmed"))
        } }.filter { it.region.isNotBlank() }
    }.getOrDefault(emptyList())

    @Synchronized fun prices(): List<DieselPrice> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PRICES, "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let {
            DieselPrice(it.optString("region"), it.optString("amount"), it.optString("currency"),
                it.optString("unit"), it.optString("validFrom"), it.optString("validUntil"))
        } }.filter { it.region.isNotBlank() && it.amount.isNotBlank() }
    }.getOrDefault(emptyList())

    @Synchronized fun audit(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_AUDIT, "[]"))
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.reversed()
    }.getOrDefault(emptyList())

    @Synchronized fun saveCoverage(entries: List<CoverageEntry>, actor: Long) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("region", it.region).put("confirmed", it.confirmed)) }
        val old = coverage().joinToString { "${it.region}:${if (it.confirmed) "مؤكدة" else "تحتاج تحقق"}" }
        val new = entries.joinToString { "${it.region}:${if (it.confirmed) "مؤكدة" else "تحتاج تحقق"}" }
        prefs.edit().putString(KEY_COVERAGE, array.toString()).commit()
        addAudit("تحديث نطاق التغطية؛ قبل=[$old]؛ بعد=[$new]؛ المدير=$actor")
    }

    @Synchronized fun savePrice(price: DieselPrice, actor: Long) {
        val current = prices().filterNot { it.region.equals(price.region, ignoreCase = true) }
        val array = JSONArray()
        (current + price).forEach { array.put(priceJson(it)) }
        prefs.edit().putString(KEY_PRICES, array.toString()).commit()
        addAudit("اعتماد سعر ${price.region}: ${price.amount} ${price.currency}/${price.unit} صالح ${price.validFrom}..${price.validUntil}؛ المدير=$actor")
    }

    fun assistantContext(): String {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val regions = coverage().joinToString("؛ ") {
            "${it.region} (${if (it.confirmed) "تغطية معتمدة" else "تتطلب تحققاً قبل التأكيد"})"
        }.ifBlank { "لم تُدخل مناطق تغطية" }
        val active = prices().filter { price ->
            val from = parseDate(price.validFrom)
            val until = parseDate(price.validUntil)
            from != null && until != null && !today.isBefore(from) && !today.isAfter(until)
        }
        val priceText = active.joinToString("؛ ") {
            "${it.region}: ${it.amount} ${it.currency} لكل ${it.unit} (معتمد وساري حتى ${it.validUntil})"
        }.ifBlank { "لا يوجد سعر افتراضي معتمد وساري؛ لا تذكر سعراً رقمياً" }
        return "تاريخ التحقق UTC: $today. نطاق التغطية المسجل: $regions. الأسعار الافتراضية المعتمدة والسارية فقط: $priceText. الأسعار والتغطية بيانات أولية وليست عرضاً نهائياً؛ لا تؤكد التوفر الفعلي أو النقل أو السعر النهائي دون اعتماد الإدارة."
    }

    private fun addAudit(line: String) {
        val old = runCatching { JSONArray(prefs.getString(KEY_AUDIT, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        next.put("${Instant.now()} | $line")
        for (i in 0 until minOf(old.length(), MAX_AUDIT - 1)) next.put(old.optString(i))
        prefs.edit().putString(KEY_AUDIT, next.toString()).commit()
    }

    private fun priceJson(price: DieselPrice) = JSONObject()
        .put("region", price.region).put("amount", price.amount).put("currency", price.currency)
        .put("unit", price.unit).put("validFrom", price.validFrom).put("validUntil", price.validUntil)

    private fun parseDate(value: String) = try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }

    companion object {
        private const val KEY_COVERAGE = "coverage_json"
        private const val KEY_PRICES = "prices_json"
        private const val KEY_AUDIT = "audit_json"
        private const val MAX_AUDIT = 30
    }
}

/** Inline-keyboard wizard; callers must authenticate the Telegram sender before routing here. */
internal class SalesAdminController(private val store: SalesAdminStore) {
    private sealed interface Pending {
        data object Coverage : Pending
        data object Price : Pending
    }
    private var pending: Pending? = null
    private var stagedCoverage: List<CoverageEntry>? = null
    private var stagedPrice: DieselPrice? = null
    private var stagedToken: String? = null

    fun handleText(text: String, actor: Long): AdminReply? {
        val command = text.trim().substringBefore(' ').substringBefore('@').lowercase()
        if (command == "/admin") {
            pending = null; stagedCoverage = null; stagedPrice = null; stagedToken = null
            return menu()
        }
        if (command == "/cancel") {
            pending = null; stagedCoverage = null; stagedPrice = null; stagedToken = null
            return AdminReply("تم إلغاء التعديل غير المحفوظ.", menu().buttons)
        }
        return when (pending) {
            Pending.Coverage -> parseCoverage(text)
            Pending.Price -> parsePrice(text)
            null -> null
        }
    }

    fun handleCallback(data: String, actor: Long): AdminReply {
        if ((data.startsWith("sales:save_coverage:") || data.startsWith("sales:save_price:")) &&
            data.substringAfterLast(':') != stagedToken) {
            return AdminReply("زر الاعتماد قديم أو لا يطابق المعاينة الحالية؛ راجع /admin.", menu().buttons)
        }
        return when (data) {
        "sales:menu" -> menu()
        "sales:coverage" -> {
            pending = Pending.Coverage; stagedCoverage = null; stagedPrice = null; stagedToken = null
            AdminReply("أرسل قائمة التغطية كاملة، منطقة واحدة في كل سطر بهذا الشكل:\nالمدينة | مؤكدة\nالمدينة الأخرى | تحتاج تحقق\n\nلإزالة كل المناطق أرسل: مسح\nأرسل /cancel للإلغاء. لم يُحفظ شيء بعد.")
        }
        "sales:price" -> {
            pending = Pending.Price; stagedCoverage = null; stagedPrice = null; stagedToken = null
            AdminReply("أرسل سعراً واحداً بهذا الترتيب:\nالمنطقة | المبلغ | رمز العملة (مثال YER) | الوحدة (مثال لتر) | بداية الصلاحية YYYY-MM-DD | نهاية الصلاحية YYYY-MM-DD\nمثال: صنعاء | 500 | YER | لتر | 2026-11-25 | 2026-12-05\nإذا وُجد سعر للمنطقة نفسها فسيستبدل. أرسل /cancel للإلغاء. لم يُحفظ شيء بعد.")
        }
        "sales:view" -> view()
        "sales:audit" -> AdminReply(store.audit().take(15).joinToString("\n").ifBlank { "لا توجد تعديلات مسجلة." }.take(3_800), menu().buttons)
        "sales:save_coverage:$stagedToken" -> {
            val candidate = stagedCoverage ?: return AdminReply("انتهت جلسة التعديل؛ ابدأ من /admin.", menu().buttons)
            store.saveCoverage(candidate, actor)
            pending = null; stagedCoverage = null; stagedToken = null
            AdminReply("تم اعتماد قائمة التغطية وتسجيل التعديل. المناطق التي تتطلب تحققاً ستبقى غير مؤكدة أمام Gemini.", menu().buttons)
        }
        "sales:save_price:$stagedToken" -> {
            val candidate = stagedPrice ?: return AdminReply("انتهت جلسة التعديل؛ ابدأ من /admin.", menu().buttons)
            store.savePrice(candidate, actor)
            pending = null; stagedPrice = null; stagedToken = null
            AdminReply("تم حفظ السعر واعتماده حسب فترة الصلاحية، وسُجل التعديل. سيُتاح لـGemini ذكره فقط أثناء سريانه، مع التنبيه أنه ليس عرضاً نهائياً.", menu().buttons)
        }
        "sales:cancel" -> {
            pending = null; stagedCoverage = null; stagedPrice = null; stagedToken = null
            AdminReply("أُلغي التعديل؛ لم يتم حفظه.", menu().buttons)
        }
        else -> AdminReply("زر غير معروف أو انتهت صلاحية الجلسة.", menu().buttons)
        }
    }

    private fun parseCoverage(text: String): AdminReply {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size == 1 && lines[0].equals("مسح", ignoreCase = true)) {
            stagedCoverage = emptyList()
        } else {
            if (lines.isEmpty() || lines.size > 80) return AdminReply("أرسل من منطقة واحدة إلى 80 منطقة، أو «مسح» لإزالة القائمة.")
            val entries = mutableListOf<CoverageEntry>()
            for (line in lines) {
                val fields = line.split('|').map(String::trim)
                if (fields.size != 2 || fields[0].length !in 2..80) return AdminReply("صيغة غير صحيحة في: ${line.take(100)}. استخدم: المنطقة | مؤكدة أو المنطقة | تحتاج تحقق")
                val status = fields[1].lowercase()
                val confirmed = when (status) {
                    "مؤكدة", "مؤكد", "confirmed" -> true
                    "تحتاج تحقق", "تتطلب تحقق", "غير مؤكدة", "needs_check" -> false
                    else -> return AdminReply("الحالة «${fields[1]}» غير معروفة. استخدم «مؤكدة» أو «تحتاج تحقق».")
                }
                if (entries.any { it.region.equals(fields[0], ignoreCase = true) }) return AdminReply("المنطقة ${fields[0]} مكررة في القائمة.")
                entries += CoverageEntry(fields[0], confirmed)
            }
            stagedCoverage = entries
        }
        pending = null
        stagedToken = UUID.randomUUID().toString().take(12)
        val summary = stagedCoverage.orEmpty().joinToString("\n") { "• ${it.region}: ${if (it.confirmed) "مؤكدة" else "تحتاج تحقق"}" }.ifBlank { "• لا توجد مناطق (سيُمسح النطاق الحالي)" }
        return AdminReply("راجع القائمة الجديدة؛ ستستبدل القائمة الحالية بالكامل:\n$summary\n\nهل تعتمدها؟", confirmKeyboard("sales:save_coverage:$stagedToken"))
    }

    private fun parsePrice(text: String): AdminReply {
        val f = text.split('|').map(String::trim)
        if (f.size != 6) return AdminReply("أحتاج 6 حقول مفصولة بعلامة |: المنطقة | المبلغ | العملة | الوحدة | بداية YYYY-MM-DD | نهاية YYYY-MM-DD")
        val amount = runCatching { BigDecimal(f[1]) }.getOrNull()
        if (f[0].length !in 2..80 || amount == null || amount <= BigDecimal.ZERO || amount.scale().coerceAtLeast(0) > 4)
            return AdminReply("المنطقة أو المبلغ غير صالح. المبلغ يجب أن يكون رقماً موجباً وبحد أقصى 4 منازل عشرية.")
        if (!f[2].matches(Regex("[A-Za-z]{3}")) || f[3].length !in 1..24)
            return AdminReply("رمز العملة يجب أن يتكون من 3 أحرف (مثل YER)، والوحدة حتى 24 حرفاً (مثل لتر).")
        val from = parseIsoDate(f[4]) ?: return AdminReply("تاريخ البداية غير صالح؛ استخدم YYYY-MM-DD.")
        val until = parseIsoDate(f[5]) ?: return AdminReply("تاريخ النهاية غير صالح؛ استخدم YYYY-MM-DD.")
        if (until.isBefore(from)) return AdminReply("تاريخ النهاية يجب أن يساوي أو يأتي بعد تاريخ البداية.")
        stagedPrice = DieselPrice(f[0], amount.stripTrailingZeros().toPlainString(), f[2].uppercase(), f[3], from.toString(), until.toString())
        pending = null
        stagedToken = UUID.randomUUID().toString().take(12)
        val p = stagedPrice!!
        return AdminReply("معاينة السعر المراد اعتماده:\nالمنطقة: ${p.region}\nالسعر: ${p.amount} ${p.currency} لكل ${p.unit}\nالصلاحية: ${p.validFrom} إلى ${p.validUntil}\n\nهذا السعر ليس عرضاً نهائياً. هل تعتمد التحديث؟", confirmKeyboard("sales:save_price:$stagedToken"))
    }

    private fun view(): AdminReply {
        val coverage = store.coverage().joinToString("\n") { "• ${it.region}: ${if (it.confirmed) "مؤكدة" else "تتطلب تحققاً"}" }.ifBlank { "• لم تُسجل مناطق تغطية" }
        val prices = store.prices().joinToString("\n") { "• ${it.region}: ${it.amount} ${it.currency}/${it.unit} (${it.validFrom} إلى ${it.validUntil})" }.ifBlank { "• لا توجد أسعار مسجلة" }
        return AdminReply("نطاق التغطية:\n$coverage\n\nالأسعار المسجلة (لا يُذكر منها إلا الساري اليوم):\n$prices", menu().buttons)
    }

    private fun menu() = AdminReply("لوحة إعدادات مبيعات الديزل\nاختر ما تريد تعديله أو مراجعته:", listOf(
        listOf(AdminButton("تحديث نطاق التغطية", "sales:coverage")),
        listOf(AdminButton("إضافة / تحديث سعر", "sales:price")),
        listOf(AdminButton("عرض الإعدادات", "sales:view"), AdminButton("سجل التعديلات", "sales:audit")),
    ))

    private fun confirmKeyboard(saveData: String) = listOf(
        listOf(AdminButton("اعتماد التغيير", saveData), AdminButton("إلغاء", "sales:cancel")),
    )

    private fun parseIsoDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }
}
