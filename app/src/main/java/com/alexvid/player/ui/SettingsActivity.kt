package com.alexvid.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.alexvid.player.BuildConfig
import com.alexvid.player.R
import com.alexvid.player.data.PlayerPreferences
import com.alexvid.player.databinding.ActivitySettingsBinding
import com.alexvid.player.databinding.ItemSettingOptionBinding
import com.alexvid.player.databinding.ItemSettingSwitchBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PlayerPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PlayerPreferences(this)
        binding.toolbarSettings.setNavigationOnClickListener { finish() }

        buildAllSections()
    }

    private fun buildAllSections() {
        binding.settingsContainer.removeAllViews()

        // 1. ВОСПРОИЗВЕДЕНИЕ
        val playbackCard = addSection("Воспроизведение")
        addSwitch(
            playbackCard,
            title = "Продолжать с места остановки",
            subtitle = "Автоматически запоминать позицию в каждом видео",
            checked = prefs.resumePlayback
        ) { prefs.resumePlayback = it }

        addSwitch(
            playbackCard,
            title = "Автопереход к следующему видео",
            subtitle = "Включать следующий файл в папке после окончания",
            checked = prefs.autoPlayNext
        ) { prefs.autoPlayNext = it }

        addOption(
            playbackCard,
            title = "Скорость по умолчанию",
            subtitle = "Начальная скорость при открытии видео",
            value = "${prefs.defaultSpeed}x"
        ) { tvVal ->
            val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val labels = speeds.map { "${it}x" }.toTypedArray()
            val cur = speeds.indexOfFirst { abs(it - prefs.defaultSpeed) < 0.05f }.coerceAtLeast(2)
            showChoiceDialog("Скорость по умолчанию", labels, cur) { idx ->
                prefs.defaultSpeed = speeds[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            playbackCard,
            title = "Шаг быстрой перемотки",
            subtitle = "Для двойного тапа и кнопок перемотки",
            value = "${prefs.seekStepSeconds} сек"
        ) { tvVal ->
            val steps = intArrayOf(5, 10, 15, 30, 60)
            val labels = steps.map { "$it сек" }.toTypedArray()
            val cur = steps.indexOf(prefs.seekStepSeconds).coerceAtLeast(1)
            showChoiceDialog("Шаг быстрой перемотки", labels, cur) { idx ->
                prefs.seekStepSeconds = steps[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            playbackCard,
            title = "Ориентация экрана",
            subtitle = "Положение экрана при запуске плеера",
            value = orientationLabel(prefs.orientationMode)
        ) { tvVal ->
            val labels = arrayOf("Авто (датчик)", "Горизонтальная", "Вертикальная", "Как в системе")
            showChoiceDialog("Ориентация экрана", labels, prefs.orientationMode.coerceIn(0, 3)) { idx ->
                prefs.orientationMode = idx
                tvVal.text = labels[idx]
            }
        }

        addOption(
            playbackCard,
            title = "Соотношение сторон по умолчанию",
            subtitle = "Масштаб видео при открытии",
            value = aspectLabel(prefs.defaultAspectIndex)
        ) { tvVal ->
            val labels = arrayOf("Вписать", "По экрану", "Заполнить", "16:9", "4:3", "Оригинал")
            showChoiceDialog("Соотношение сторон", labels, prefs.defaultAspectIndex) { idx ->
                prefs.defaultAspectIndex = idx
                tvVal.text = labels[idx]
            }
        }

        addSwitch(
            playbackCard,
            title = "Фоновое воспроизведение",
            subtitle = "Продолжать звук при сворачивании или выключении экрана",
            checked = prefs.backgroundPlay
        ) { prefs.backgroundPlay = it }

        addSwitch(
            playbackCard,
            title = "Не выключать экран",
            subtitle = "Экран остаётся активным во время просмотра",
            checked = prefs.keepScreenOn,
            isLast = true
        ) { prefs.keepScreenOn = it }

        // 2. ВИДЕО И ДЕКОДИРОВАНИЕ
        val videoCard = addSection("Видео и декодирование")
        addOption(
            videoCard,
            title = "Аппаратное декодирование",
            subtitle = "HW экономит батарею, FFmpeg играет редкие форматы",
            value = hwLabel(prefs.hwDecodingMode)
        ) { tvVal ->
            val labels = arrayOf("Авто (HW + FFmpeg)", "Полное аппаратное", "Программное (FFmpeg)")
            showChoiceDialog("Аппаратное декодирование", labels, prefs.hwDecodingMode.coerceIn(0, 2)) { idx ->
                prefs.hwDecodingMode = idx
                tvVal.text = labels[idx]
            }
        }

        addOption(
            videoCard,
            title = "Деинтерлейсинг (чересстрочность)",
            subtitle = "Убирает «гребёнку» на IPTV, TS и DVD видео",
            value = deinterlaceLabel(prefs.deinterlaceMode),
            isLast = true
        ) { tvVal ->
            val labels = arrayOf("Авто", "Выключен", "Включён")
            showChoiceDialog("Деинтерлейсинг", labels, prefs.deinterlaceMode.coerceIn(0, 2)) { idx ->
                prefs.deinterlaceMode = idx
                tvVal.text = labels[idx]
            }
        }

        // 3. ЗВУК
        val audioCard = addSection("Звук")
        addSwitch(
            audioCard,
            title = "Усиление громкости до 200%",
            subtitle = "Программный буст звука сверх системного максимума",
            checked = prefs.volumeBoostEnabled
        ) { prefs.volumeBoostEnabled = it }

        addSwitch(
            audioCard,
            title = "Сохранять тембр при ускорении",
            subtitle = "Audio Time-Stretch для естественных голосов на 1.5x–2x",
            checked = prefs.audioTimeStretch
        ) { prefs.audioTimeStretch = it }

        addOption(
            audioCard,
            title = "Задержка звука по умолчанию",
            subtitle = "Синхронизация для Bluetooth-наушников и колонок",
            value = formatDelayMs(prefs.defaultAudioDelayMs),
            isLast = true
        ) { tvVal ->
            val delays = intArrayOf(-500, -300, -200, -100, -50, 0, 50, 100, 200, 300, 500)
            val labels = delays.map { formatDelayMs(it) }.toTypedArray()
            val cur = delays.indexOf(prefs.defaultAudioDelayMs).let { if (it >= 0) it else 5 }
            showChoiceDialog("Задержка звука", labels, cur) { idx ->
                prefs.defaultAudioDelayMs = delays[idx]
                tvVal.text = labels[idx]
            }
        }

        // 4. ЖЕСТЫ И ИНТЕРФЕЙС
        val gestureCard = addSection("Жесты и интерфейс плеера")
        addSwitch(
            gestureCard,
            title = "Жест яркости (свайп слева)",
            subtitle = "Регулировка яркости экрана вертикальным свайпом",
            checked = prefs.gestureBrightness
        ) { prefs.gestureBrightness = it }

        addSwitch(
            gestureCard,
            title = "Жест громкости (свайп справа)",
            subtitle = "Регулировка громкости вертикальным свайпом",
            checked = prefs.gestureVolume
        ) { prefs.gestureVolume = it }

        addSwitch(
            gestureCard,
            title = "Жест перемотки (горизонтальный свайп)",
            subtitle = "Плавная перемотка проведением пальца влево/вправо",
            checked = prefs.gestureSeek
        ) { prefs.gestureSeek = it }

        addSwitch(
            gestureCard,
            title = "Двойной тап (перемотка и пауза)",
            subtitle = "Двойное касание слева/справа/по центру экрана",
            checked = prefs.gestureDoubleTap
        ) { prefs.gestureDoubleTap = it }

        addSwitch(
            gestureCard,
            title = "Ускорение 2.0x при удержании",
            subtitle = "Удерживайте палец на экране для временного 2x",
            checked = prefs.gestureLongPress2x
        ) { prefs.gestureLongPress2x = it }

        addOption(
            gestureCard,
            title = "Автоскрытие кнопок управления",
            subtitle = "Через сколько секунд скрывать интерфейс плеера",
            value = hudTimeoutLabel(prefs.hudTimeoutSeconds)
        ) { tvVal ->
            val values = intArrayOf(2, 4, 6, 10, 0)
            val labels = arrayOf("2 сек", "4 сек", "6 сек", "10 сек", "Не скрывать")
            val cur = values.indexOf(prefs.hudTimeoutSeconds).coerceAtLeast(1)
            showChoiceDialog("Автоскрытие кнопок", labels, cur) { idx ->
                prefs.hudTimeoutSeconds = values[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            gestureCard,
            title = "Справка по жестам и режиму Зума",
            subtitle = "Полный список всех жестов и функций обзора",
            value = "Открыть",
            isLast = true
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gestures_guide_title)
                .setMessage(R.string.gestures_guide_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        // 5. СУБТИТРЫ
        val subCard = addSection("Субтитры")
        addSwitch(
            subCard,
            title = "Автопоиск субтитров рядом с видео",
            subtitle = "Подключать .srt/.ass/.vtt с тем же именем автоматически",
            checked = prefs.subtitleAutoLoad
        ) { prefs.subtitleAutoLoad = it }

        addOption(
            subCard,
            title = "Размер шрифта субтитров",
            subtitle = "Применяется при открытии видео",
            value = subSizeLabel(prefs.subtitleRelFontSize)
        ) { tvVal ->
            val values = intArrayOf(20, 16, 13, 10)
            val labels = arrayOf("Мелкий", "Обычный", "Крупный", "Очень крупный")
            val cur = values.indexOf(prefs.subtitleRelFontSize).let { if (it >= 0) it else 1 }
            showChoiceDialog("Размер шрифта субтитров", labels, cur) { idx ->
                prefs.subtitleRelFontSize = values[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            subCard,
            title = "Цвет текста субтитров",
            subtitle = "Основной цвет букв",
            value = subColorLabel(prefs.subtitleColor)
        ) { tvVal ->
            val values = intArrayOf(16777215, 16776960, 65535, 65280)
            val labels = arrayOf("Белый", "Жёлтый", "Голубой", "Зелёный")
            val cur = values.indexOf(prefs.subtitleColor).let { if (it >= 0) it else 0 }
            showChoiceDialog("Цвет текста субтитров", labels, cur) { idx ->
                prefs.subtitleColor = values[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            subCard,
            title = "Кодировка субтитров",
            subtitle = "Выберите Windows-1251, если русские субтитры отображаются некорректно",
            value = subEncodingLabel(prefs.subtitleEncoding)
        ) { tvVal ->
            val values = arrayOf("", "CP1251", "UTF-8", "ISO-8859-1", "UTF-16")
            val labels = arrayOf("Авто (UTF-8)", "Windows-1251 (Кириллица)", "UTF-8", "ISO-8859-1", "UTF-16")
            val cur = values.indexOf(prefs.subtitleEncoding).coerceAtLeast(0)
            showChoiceDialog("Кодировка субтитров", labels, cur) { idx ->
                prefs.subtitleEncoding = values[idx]
                tvVal.text = labels[idx]
            }
        }

        addOption(
            subCard,
            title = "Фон под субтитрами",
            subtitle = "Затемнение за текстом для лучшей читаемости",
            value = subBgLabel(prefs.subtitleBgOpacity),
            isLast = true
        ) { tvVal ->
            val values = intArrayOf(0, 96, 200)
            val labels = arrayOf("Без фона", "Полупрозрачный", "Тёмный")
            val cur = values.indexOf(prefs.subtitleBgOpacity).let { if (it >= 0) it else 1 }
            showChoiceDialog("Фон под субтитрами", labels, cur) { idx ->
                prefs.subtitleBgOpacity = values[idx]
                tvVal.text = labels[idx]
            }
        }

        // 6. ОНЛАЙН-ПОТОКИ И СЕТЬ
        val netCard = addSection("Онлайн-потоки и сеть")
        addOption(
            netCard,
            title = "Сетевой буфер (кэш)",
            subtitle = "Больше буфер — стабильнее при слабом интернете",
            value = "${prefs.networkCachingMs} мс"
        ) { tvVal ->
            val values = intArrayOf(500, 1000, 2000, 3000, 5000, 8000, 10000)
            val labels = values.map { "$it мс" }.toTypedArray()
            val cur = values.indexOf(prefs.networkCachingMs).let { if (it >= 0) it else 2 }
            showChoiceDialog("Сетевой буфер", labels, cur) { idx ->
                prefs.networkCachingMs = values[idx]
                tvVal.text = labels[idx]
            }
        }

        addSwitch(
            netCard,
            title = "Автопереподключение к потоку",
            subtitle = "Восстанавливать онлайн-видео при обрыве сети",
            checked = prefs.httpReconnect
        ) { prefs.httpReconnect = it }

        addOption(
            netCard,
            title = "User-Agent по умолчанию",
            subtitle = "Используется для онлайн-потоков, если не задан вручную",
            value = prefs.defaultUserAgent.ifBlank { "Стандартный" },
            isLast = true
        ) { tvVal ->
            val input = EditText(this).apply {
                setText(prefs.defaultUserAgent)
                hint = "Mozilla/5.0 (Android)..."
                setPadding(48, 32, 48, 32)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("User-Agent по умолчанию")
                .setView(input)
                .setPositiveButton("Сохранить") { _, _ ->
                    val ua = input.text?.toString().orEmpty().trim()
                    prefs.defaultUserAgent = ua
                    tvVal.text = ua.ifBlank { "Стандартный" }
                }
                .setNeutralButton("Сбросить") { _, _ ->
                    prefs.defaultUserAgent = ""
                    tvVal.text = "Стандартный"
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // 7. СКРИНШОТЫ И ПРИЛОЖЕНИЕ
        val appCard = addSection("Скриншоты и приложение")
        addOption(
            appCard,
            title = "Формат скриншотов",
            subtitle = "Сохраняются в альбом Галереи Pictures/ALEXVID",
            value = prefs.screenshotFormat
        ) { tvVal ->
            val values = arrayOf("PNG", "JPEG")
            val labels = arrayOf("PNG (максимальное качество)", "JPEG (меньше размер файла)")
            val cur = if (prefs.screenshotFormat.equals("JPEG", true)) 1 else 0
            showChoiceDialog("Формат скриншотов", labels, cur) { idx ->
                prefs.screenshotFormat = values[idx]
                tvVal.text = values[idx]
            }
        }

        addSwitch(
            appCard,
            title = "Вспышка при создании скриншота",
            subtitle = "Визуальный эффект затвора при захвате кадра",
            checked = prefs.screenshotFlash
        ) { prefs.screenshotFlash = it }

        addOption(
            appCard,
            title = "Сбросить все настройки",
            subtitle = "Вернуть значения по умолчанию (избранное и история сохранятся)",
            value = "Сброс"
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Сбросить настройки?")
                .setMessage("Все параметры плеера вернутся к стандартным. Избранные потоки и история останутся.")
                .setPositiveButton("Сбросить") { _, _ ->
                    prefs.resetAllSettings()
                    buildAllSections()
                    Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        addOption(
            appCard,
            title = "О приложении ALEXVID",
            subtitle = "Движок LibVLC 3.6.2 • Все форматы • Онлайн потоки",
            value = "v${BuildConfig.VERSION_NAME}",
            isLast = true
        ) {
            Toast.makeText(
                this,
                "ALEXVID v${BuildConfig.VERSION_NAME} (LibVLC 3.6.2)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // --- UI Builders ---

    private fun addSection(title: String): LinearLayout {
        val header = TextView(this).apply {
            text = title.uppercase(Locale.getDefault())
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_cyan))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(16), dp(8), dp(6))
        }
        binding.settingsContainer.addView(header)

        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_card))
            strokeColor = ContextCompat.getColor(this@SettingsActivity, R.color.bg_card_stroke)
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(inner)
        binding.settingsContainer.addView(card)
        return inner
    }

    private fun addSwitch(
        container: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        isLast: Boolean = false,
        onChanged: (Boolean) -> Unit
    ) {
        val row = ItemSettingSwitchBinding.inflate(LayoutInflater.from(this), container, false)
        row.tvTitle.text = title
        row.tvSubtitle.text = subtitle
        row.switchControl.isChecked = checked
        row.switchControl.setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        row.root.setOnClickListener { row.switchControl.toggle() }
        container.addView(row.root)
        if (!isLast) addDivider(container)
    }

    private fun addOption(
        container: LinearLayout,
        title: String,
        subtitle: String,
        value: String,
        isLast: Boolean = false,
        onClick: (TextView) -> Unit
    ) {
        val row = ItemSettingOptionBinding.inflate(LayoutInflater.from(this), container, false)
        row.tvTitle.text = title
        row.tvSubtitle.text = subtitle
        row.tvValue.text = value
        row.root.setOnClickListener { onClick(row.tvValue) }
        container.addView(row.root)
        if (!isLast) addDivider(container)
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_card_stroke))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        }
        container.addView(divider)
    }

    private fun showChoiceDialog(
        title: String,
        items: Array<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- Formatters ---

    private fun orientationLabel(mode: Int) = when (mode) {
        1 -> "Горизонтальная"
        2 -> "Вертикальная"
        3 -> "Системная"
        else -> "Авто"
    }

    private fun aspectLabel(idx: Int) = when (idx) {
        1 -> "По экрану"
        2 -> "Заполнить"
        3 -> "16:9"
        4 -> "4:3"
        5 -> "Оригинал"
        else -> "Вписать"
    }

    private fun hwLabel(mode: Int) = when (mode) {
        1 -> "Полное HW"
        2 -> "FFmpeg (SW)"
        else -> "Авто"
    }

    private fun deinterlaceLabel(mode: Int) = when (mode) {
        1 -> "Выкл"
        2 -> "Вкл"
        else -> "Авто"
    }

    private fun formatDelayMs(ms: Int): String = when {
        ms == 0 -> "0 мс"
        ms > 0 -> "+$ms мс"
        else -> "$ms мс"
    }

    private fun hudTimeoutLabel(sec: Int) = if (sec <= 0) "Не скрывать" else "$sec сек"

    private fun subSizeLabel(size: Int) = when (size) {
        20 -> "Мелкий"
        13 -> "Крупный"
        10 -> "Очень крупный"
        else -> "Обычный"
    }

    private fun subColorLabel(color: Int) = when (color) {
        16776960 -> "Жёлтый"
        65535 -> "Голубой"
        65280 -> "Зелёный"
        else -> "Белый"
    }

    private fun subEncodingLabel(enc: String) = when (enc) {
        "CP1251" -> "Win-1251"
        "UTF-8" -> "UTF-8"
        "ISO-8859-1" -> "ISO-8859-1"
        "UTF-16" -> "UTF-16"
        else -> "Авто"
    }

    private fun subBgLabel(opacity: Int) = when (opacity) {
        0 -> "Нет"
        200 -> "Тёмный"
        else -> "Полупрозр."
    }
}
