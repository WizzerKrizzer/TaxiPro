package com.taxipro.ui.screens

import androidx.compose.runtime.Composable
import com.taxipro.data.db.AppLanguage
import com.taxipro.ui.theme.LocalSettings

data class FilterUiText(
    val all: String,
    val advancedFilters: String,
    val routeFilter: String,
    val shiftFilter: String,
    val clear: String,
    val fromZone: String,
    val toZone: String,
    val km: String,
    val amount: String,
    val time: String,
    val hours: String,
    val min: String,
    val max: String,
    val minutesShort: String,
    val hoursShort: String,
    val onlyRoute: String,
    val onlyFrom: String,
    val onlyTo: String,
    val showingAllRoutes: String,
    val loadMore: String = "Load more (%d left)",
)

@Composable
fun currentFilterUiText(): FilterUiText = when (LocalSettings.current.language) {
    AppLanguage.BG -> FilterUiText(
        all = "Всички",
        advancedFilters = "Разширени филтри",
        routeFilter = "Филтър по маршрут",
        shiftFilter = "Филтър на смени",
        clear = "Изчисти",
        fromZone = "От зона",
        toZone = "До зона",
        km = "Км",
        amount = "Сума",
        time = "Време",
        hours = "Часове",
        min = "Мин",
        max = "Макс",
        minutesShort = "мин",
        hoursShort = "ч",
        onlyRoute = "Само курсове: %s → %s",
        onlyFrom = "Само курсове от: %s",
        onlyTo = "Само курсове до: %s",
        showingAllRoutes = "Показва всички начални и крайни зони",
        loadMore = "Покажи още (%d остават)",
    )
    AppLanguage.ES -> FilterUiText("Todos", "Filtros avanzados", "Filtro de ruta", "Filtro de turnos", "Borrar", "Desde zona", "A zona", "Km", "Importe", "Tiempo", "Horas", "Mín", "Máx", "min", "h", "Solo viajes: %s → %s", "Solo viajes desde: %s", "Solo viajes a: %s", "Muestra todas las zonas de inicio y fin")
    AppLanguage.PT -> FilterUiText("Todos", "Filtros avançados", "Filtro de rota", "Filtro de turnos", "Limpar", "Da zona", "Para zona", "Km", "Valor", "Tempo", "Horas", "Mín", "Máx", "min", "h", "Só corridas: %s → %s", "Só corridas de: %s", "Só corridas para: %s", "Mostra todas as zonas de início e fim")
    AppLanguage.RU -> FilterUiText("Все", "Расширенные фильтры", "Фильтр маршрута", "Фильтр смен", "Очистить", "Из зоны", "В зону", "Км", "Сумма", "Время", "Часы", "Мин", "Макс", "мин", "ч", "Только поездки: %s → %s", "Только поездки из: %s", "Только поездки в: %s", "Показаны все начальные и конечные зоны")
    AppLanguage.FR -> FilterUiText("Tous", "Filtres avancés", "Filtre d’itinéraire", "Filtre des services", "Effacer", "Zone départ", "Zone arrivée", "Km", "Montant", "Temps", "Heures", "Min", "Max", "min", "h", "Courses seulement : %s → %s", "Courses depuis : %s", "Courses vers : %s", "Affiche toutes les zones de départ et d’arrivée")
    AppLanguage.DE -> FilterUiText("Alle", "Erweiterte Filter", "Routenfilter", "Schichtfilter", "Zurücksetzen", "Von Zone", "Nach Zone", "Km", "Betrag", "Zeit", "Stunden", "Min", "Max", "Min", "Std", "Nur Fahrten: %s → %s", "Nur Fahrten von: %s", "Nur Fahrten nach: %s", "Zeigt alle Start- und Zielzonen")
    AppLanguage.TR -> FilterUiText("Tümü", "Gelişmiş filtreler", "Rota filtresi", "Vardiya filtresi", "Temizle", "Başlangıç bölgesi", "Varış bölgesi", "Km", "Tutar", "Süre", "Saat", "Min", "Maks", "dk", "sa", "Sadece yolculuklar: %s → %s", "Sadece şuradan: %s", "Sadece şuraya: %s", "Tüm başlangıç ve varış bölgelerini gösterir")
    AppLanguage.IT -> FilterUiText("Tutti", "Filtri avanzati", "Filtro percorso", "Filtro turni", "Cancella", "Da zona", "A zona", "Km", "Importo", "Tempo", "Ore", "Min", "Max", "min", "h", "Solo corse: %s → %s", "Solo corse da: %s", "Solo corse verso: %s", "Mostra tutte le zone di partenza e arrivo")
    AppLanguage.ID -> FilterUiText("Semua", "Filter lanjutan", "Filter rute", "Filter shift", "Hapus", "Dari zona", "Ke zona", "Km", "Jumlah", "Waktu", "Jam", "Min", "Maks", "mnt", "j", "Hanya perjalanan: %s → %s", "Hanya perjalanan dari: %s", "Hanya perjalanan ke: %s", "Menampilkan semua zona awal dan akhir")
    AppLanguage.VI -> FilterUiText("Tất cả", "Bộ lọc nâng cao", "Lọc tuyến", "Lọc ca", "Xóa", "Từ khu vực", "Đến khu vực", "Km", "Số tiền", "Thời gian", "Giờ", "Tối thiểu", "Tối đa", "phút", "giờ", "Chỉ chuyến: %s → %s", "Chỉ chuyến từ: %s", "Chỉ chuyến đến: %s", "Hiển thị tất cả khu vực đầu và cuối")
    AppLanguage.KO -> FilterUiText("전체", "고급 필터", "경로 필터", "근무 필터", "지우기", "출발 구역", "도착 구역", "Km", "금액", "시간", "시간", "최소", "최대", "분", "시간", "운행만: %s → %s", "출발 운행만: %s", "도착 운행만: %s", "모든 출발 및 도착 구역 표시")
    AppLanguage.JA -> FilterUiText("すべて", "詳細フィルター", "ルートフィルター", "シフトフィルター", "クリア", "出発ゾーン", "到着ゾーン", "Km", "金額", "時間", "時間", "最小", "最大", "分", "時間", "乗車のみ: %s → %s", "%s からの乗車のみ", "%s への乗車のみ", "すべての出発/到着ゾーンを表示")
    AppLanguage.ZH -> FilterUiText("全部", "高级筛选", "路线筛选", "班次筛选", "清除", "出发区域", "到达区域", "公里", "金额", "时间", "小时", "最小", "最大", "分钟", "小时", "仅行程: %s → %s", "仅从 %s 出发", "仅到 %s", "显示所有起点和终点区域")
    AppLanguage.HI -> FilterUiText("सभी", "उन्नत फ़िल्टर", "रूट फ़िल्टर", "शिफ्ट फ़िल्टर", "साफ़ करें", "ज़ोन से", "ज़ोन तक", "किमी", "राशि", "समय", "घंटे", "न्यून", "अधिक", "मिनट", "घं", "केवल यात्राएं: %s → %s", "केवल यहां से: %s", "केवल यहां तक: %s", "सभी प्रारंभ और अंत ज़ोन दिखाता है")
    AppLanguage.AR -> FilterUiText("الكل", "فلاتر متقدمة", "فلتر المسار", "فلتر الورديات", "مسح", "من منطقة", "إلى منطقة", "كم", "المبلغ", "الوقت", "ساعات", "أدنى", "أقصى", "د", "س", "رحلات فقط: %s → %s", "رحلات من: %s", "رحلات إلى: %s", "يعرض كل مناطق البداية والنهاية")
    else -> FilterUiText(
        all = "All",
        advancedFilters = "Advanced filters",
        routeFilter = "Route filter",
        shiftFilter = "Shift filter",
        clear = "Clear",
        fromZone = "From zone",
        toZone = "To zone",
        km = "Km",
        amount = "Amount",
        time = "Time",
        hours = "Hours",
        min = "Min",
        max = "Max",
        minutesShort = "min",
        hoursShort = "h",
        onlyRoute = "Only rides: %s → %s",
        onlyFrom = "Only rides from: %s",
        onlyTo = "Only rides to: %s",
        showingAllRoutes = "Showing all start and end zones",
        loadMore = "Load more (%d left)",
    )
}
