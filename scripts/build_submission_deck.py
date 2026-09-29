"""Build the editable competition deck from verified local artifacts.

Requires python-pptx and Pillow. The map screenshot in submission/assets is
captured from the completed v28 job; the verified GeoJSON builds its fallback.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Inches, Pt


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "submission"
TOTAL_SLIDES = 9
INPUT = ROOT / "inputs" / "ТЗ" / "Датасет скорректированный.geojson"
REPORT = ROOT / "artifacts" / "bench" / "group-focused-v28-final-report.json"
EXPORT = ROOT / "artifacts" / "bench" / "group-focused-v28-final-report.json.export.geojson"
MAP_SCREENSHOT = OUT / "assets" / "map-v28-detail.png"

NAVY = "0C202B"
DARK = "102D39"
DEEP = "09202A"
TEAL = "17AC99"
MINT = "94DED0"
CORAL = "F18A61"
CREAM = "F7FAF8"
INK = "173744"
MUTED = "68828B"
PALE = "E8F2F0"
WHITE = "FFFFFF"


def flat(shape):
    """PowerPoint's default rounded-rectangle theme adds an unwanted shadow."""
    for effect in shape._element.xpath(".//a:effectRef"):
        effect.set("idx", "0")
    return shape


def rgb(value):
    return RGBColor.from_string(value)


def rect(slide, x, y, w, h, fill, radius=False, line=None, line_width=0.8):
    shape = slide.shapes.add_shape(
        MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE,
        Inches(x), Inches(y), Inches(w), Inches(h),
    )
    shape.fill.solid()
    shape.fill.fore_color.rgb = rgb(fill)
    if line:
        shape.line.color.rgb = rgb(line)
        shape.line.width = Pt(line_width)
    else:
        shape.line.fill.background()
    if radius:
        shape.adjustments[0] = 0.08
    return flat(shape)


def text(slide, value, x, y, w, h, size=18, color=INK, bold=False,
         align=PP_ALIGN.LEFT, valign=MSO_ANCHOR.MIDDLE, font="Aptos",
         margin=0, spacing=1.05):
    shape = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = shape.text_frame
    frame.clear()
    frame.word_wrap = True
    frame.margin_left = frame.margin_right = Inches(margin)
    frame.margin_top = frame.margin_bottom = 0
    frame.vertical_anchor = valign
    for i, paragraph_text in enumerate(value.split("\n")):
        p = frame.paragraphs[0] if i == 0 else frame.add_paragraph()
        p.text = paragraph_text
        p.alignment = align
        p.space_after = Pt(0)
        p.line_spacing = spacing
        for run in p.runs:
            run.font.name = font
            run.font.size = Pt(size)
            run.font.bold = bold
            run.font.color.rgb = rgb(color)
    return flat(shape)


def line(slide, x1, y1, x2, y2, color=TEAL, width=3):
    shape = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT,
                                       Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    shape.line.color.rgb = rgb(color)
    shape.line.width = Pt(width)
    return flat(shape)


def circle(slide, x, y, diameter, fill, border=None):
    shape = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x), Inches(y),
                                   Inches(diameter), Inches(diameter))
    shape.fill.solid()
    shape.fill.fore_color.rgb = rgb(fill)
    if border:
        shape.line.color.rgb = rgb(border)
        shape.line.width = Pt(1.4)
    else:
        shape.line.fill.background()
    return flat(shape)


def slide_base(prs, number, label, title, subtitle=None, dark=False):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    rect(slide, 0, 0, 13.333, 7.5, NAVY if dark else CREAM)
    color = MINT if dark else TEAL
    title_color = WHITE if dark else INK
    sub_color = "BCD0D0" if dark else MUTED
    text(slide, label.upper(), 0.68, 0.31, 10.5, 0.27, 9, color, True)
    text(slide, title, 0.68, 0.72, 11.9, 0.62, 31, title_color, True)
    if subtitle:
        text(slide, subtitle, 0.69, 1.38, 11.9, 0.44, 14, sub_color)
    rect(slide, 0.68, 7.11, 11.97, 0.012, "31515A" if dark else "CEDDDC")
    text(slide, "ТРАССИРОВКА ТЕПЛОВЫХ СЕТЕЙ", 0.68, 7.18, 6.8, 0.16,
         8, sub_color, True)
    text(slide, f"{number:02d} / {TOTAL_SLIDES:02d}", 11.6, 7.18, 1.05, 0.16,
         8, sub_color, True, PP_ALIGN.RIGHT)
    return slide


def card(slide, x, y, w, h, tag, title, body, accent=TEAL):
    rect(slide, x, y, w, h, WHITE, radius=True, line="DAE7E4")
    rect(slide, x + 0.23, y + 0.23, 0.07, 0.32, accent)
    text(slide, tag, x + 0.43, y + 0.24, w - 0.72, 0.23, 11, accent, True)
    text(slide, title, x + 0.25, y + 0.73, w - 0.50, 0.57, 20, INK, True)
    text(slide, body, x + 0.25, y + 1.39, w - 0.50, h - 1.57,
         15, MUTED, valign=MSO_ANCHOR.TOP)


def points(geometry):
    kind = geometry["type"]
    coords = geometry["coordinates"]
    if kind == "Point":
        return [coords]
    if kind == "LineString":
        return coords
    if kind == "Polygon":
        return [point for ring in coords for point in ring]
    if kind == "MultiPolygon":
        return [point for polygon in coords for ring in polygon for point in ring]
    return []


def map_image(data, exported, variant_id, connected, run_label):
    size = (1600, 1050)
    image = Image.new("RGB", size, "#E7F1EE")
    draw = ImageDraw.Draw(image)
    source = [f for f in data["features"] if f["properties"].get("object_type") == "source"]
    targets = [f for f in data["features"] if f["properties"].get("object_type") == "oks_connection_point"]
    route = [f for f in exported["features"] if f["properties"].get("variant_id") == variant_id
             and f["properties"].get("object_type") == "heat_network"]
    basis = [point for feature in targets + source + route for point in points(feature["geometry"])]
    lat0 = sum(point[1] for point in basis) / len(basis)
    projection = lambda pair: (pair[0] * math.cos(math.radians(lat0)), pair[1])
    projected = [projection(point) for point in basis]
    xs, ys = zip(*projected)
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    dx, dy = max_x - min_x, max_y - min_y
    min_x, max_x = min_x - dx * 0.09, max_x + dx * 0.09
    min_y, max_y = min_y - dy * 0.10, max_y + dy * 0.10
    scale = min((size[0] - 130) / (max_x - min_x), (size[1] - 130) / (max_y - min_y))
    ox = (size[0] - (max_x - min_x) * scale) / 2
    oy = (size[1] - (max_y - min_y) * scale) / 2

    def xy(point):
        x, y = projection(point)
        return (round(ox + (x - min_x) * scale),
                round(size[1] - oy - (y - min_y) * scale))

    for x in range(0, size[0], 100):
        draw.line((x, 0, x, size[1]), fill="#DCE9E5", width=2)
    for y in range(0, size[1], 100):
        draw.line((0, y, size[0], y), fill="#DCE9E5", width=2)

    for feature in data["features"]:
        props = feature["properties"]
        if props.get("object_type") != "restriction":
            continue
        fill = "#C4D9D6" if props.get("restriction_type") == "oks" else "#D5E7DF"
        for polygon in feature["geometry"]["coordinates"]:
            exterior = [xy(point) for point in polygon[0]]
            draw.polygon(exterior, fill=fill)
            draw.line(exterior, fill="#799D9E", width=3, joint="curve")
            for hole in polygon[1:]:
                interior = [xy(point) for point in hole]
                draw.polygon(interior, fill="#E7F1EE")
                draw.line(interior, fill="#799D9E", width=2)

    for feature in data["features"]:
        if feature["properties"].get("object_type") == "heat_network":
            draw.line([xy(point) for point in feature["geometry"]["coordinates"]],
                      fill="#5F818A", width=6, joint="curve")
    for feature in route:
        coords = [xy(point) for point in feature["geometry"]["coordinates"]]
        draw.line(coords, fill="#FFFFFF", width=16, joint="curve")
        draw.line(coords, fill="#039B84", width=9, joint="curve")

    for feature in targets:
        point = xy(feature["geometry"]["coordinates"])
        is_connected = feature["properties"].get("id") in connected
        fill = "#079F89" if is_connected else "#E8765E"
        draw.ellipse((point[0] - 8, point[1] - 8, point[0] + 8, point[1] + 8),
                     fill=fill, outline="white", width=3)
    for feature in source:
        point = xy(feature["geometry"]["coordinates"])
        draw.ellipse((point[0] - 14, point[1] - 14, point[0] + 14, point[1] + 14),
                     fill="#EF7F58", outline="white", width=4)
    font_path = Path("C:/Windows/Fonts/arialbd.ttf")
    font = ImageFont.truetype(str(font_path), 21) if font_path.exists() else ImageFont.load_default()
    draw.rounded_rectangle((34, 32, 441, 100), radius=20, fill="#FFFFFF")
    draw.text((56, 54), f"Контрольный маршрут {run_label}", font=font, fill="#173744")
    path = ROOT / "artifacts" / "deck-preview" / "route-illustration.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    return path


def make():
    OUT.mkdir(parents=True, exist_ok=True)
    data = json.loads(INPUT.read_text(encoding="utf-8"))
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    exported = json.loads(EXPORT.read_text(encoding="utf-8"))
    maximum = report["maximumCoverage"]
    assert report["jobStatus"] == "SUCCEEDED"
    assert any(check["variantId"] == maximum["variantId"]
               and check["connectedCount"] == maximum["connectedCount"]
               for check in report["independentExportChecks"])
    run_label = "v28"
    map_path = map_image(data, exported, maximum["variantId"],
                         set(maximum["connectedTargetIds"]), run_label)
    evidence_map = MAP_SCREENSHOT if MAP_SCREENSHOT.exists() else map_path

    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)
    prs.core_properties.title = "Трассировка тепловых сетей"
    prs.core_properties.subject = "Сервис моделирования технологического присоединения"
    prs.core_properties.author = "Команда проекта"

    # 1 — cover
    s = slide_base(prs, 1, "Конкурсный проект", "Трассировка тепловых сетей",
                   "Проверяемая модель подключений для новых зданий", dark=True)
    text(s, "14/17", 0.78, 2.38, 4.90, 1.18, 58, MINT, True)
    text(s, "зданий подключено в полном API-прогоне", 0.80, 3.58, 4.75, 0.56,
         19, WHITE, True)
    rect(s, 0.80, 4.48, 4.72, 0.025, "4C6A71")
    text(s, "ОБЩИЙ СТВОЛ  /  КОРОТКИЕ ОТВОДЫ", 0.80, 4.77, 4.80, 0.28,
         11.5, MINT, True)
    text(s, "Сервис сравнивает допустимые сети по охвату и полной стоимости.",
         0.80, 5.20, 4.73, 0.78, 17, "C7D8D8")
    s.shapes.add_picture(str(evidence_map), Inches(6.00), Inches(2.18),
                         width=Inches(6.60), height=Inches(3.62))
    text(s, "Фрагмент проверенного варианта v28 · карта © OpenStreetMap contributors",
         6.00, 5.96, 6.60, 0.31, 11, "BDD1D1")

    # 2 — problem and value
    s = slide_base(prs, 2, "Проблема", "Три вещи приходится решать вместе",
                   "Короткая труба к одному зданию может помешать подключить остальные.")
    card(s, 0.75, 2.14, 3.83, 2.90, "01 / ДАННЫЕ", "Город и запреты",
         "Здания, существующие трубы, камеры и специальные проходы — в одной геометрии.")
    card(s, 4.75, 2.14, 3.83, 2.90, "02 / ТОПОЛОГИЯ", "Сеть целиком",
         "Ствол и отводы выбираются вместе: одна труба должна помогать следующим зданиям.", CORAL)
    card(s, 8.75, 2.14, 3.83, 2.90, "03 / ЭКОНОМИКА", "Полная цена",
         "Считаем строительство, камеры и штраф за каждое неподключённое здание.")
    rect(s, 0.75, 5.39, 11.83, 1.14, DARK, radius=True)
    text(s, "Что получает инженер", 1.05, 5.60, 3.0, 0.29, 12, MINT, True)
    text(s, "Допустимые варианты · причины отказов · GeoJSON для проверки",
         1.05, 5.96, 10.95, 0.36, 20, WHITE, True)

    # 3 — data and pipeline
    s = slide_base(prs, 3, "Вход и процесс", "Один файл → прозрачный расчёт",
                   "Данные принимаются без ручной правки конкурсных объектов.")
    stats = [("144", "объекта"), ("17", "целей"), ("85", "контуров ОКС")]
    for i, (number, label) in enumerate(stats):
        x = 0.78 + 4.05 * i
        rect(s, x, 2.02, 3.77, 1.32, WHITE, radius=True, line="D9E7E4")
        text(s, number, x + 0.25, 2.20, 1.27, 0.75, 34, TEAL, True)
        text(s, label, x + 1.46, 2.37, 2.05, 0.40, 16, INK, True)
    stages = ["GeoJSON", "Врезки", "Коридоры", "Проверка", "Варианты"]
    for i, label in enumerate(stages):
        x = 0.76 + i * 2.46
        rect(s, x, 4.05, 2.12, 1.12, DARK if i == 3 else PALE, radius=True)
        text(s, f"{i+1:02d}", x + 0.16, 4.16, 0.45, 0.26, 11,
             MINT if i == 3 else TEAL, True)
        text(s, label, x + 0.16, 4.47, 1.80, 0.41, 18,
             WHITE if i == 3 else INK, True)
        if i < len(stages) - 1:
            text(s, "→", x + 2.15, 4.38, 0.27, 0.44, 24, TEAL, True)
    text(s, "Расстояния — EPSG:32637   •   API и экспорт — WGS84   •   Вход — FeatureCollection",
         0.78, 5.76, 11.8, 0.52, 15, MUTED)

    # 4 — tree-first logic
    s = slide_base(prs, 4, "Алгоритм", "Сначала магистраль, затем отводы",
                   "Несколько групп и стволов — если это дешевле и подключает больше домов.")
    rect(s, 0.75, 2.03, 7.82, 4.54, WHITE, radius=True, line="D9E7E4")
    # One source can feed two corridors; every short spur can itself branch.
    circle(s, 1.03, 3.99, 0.73, CORAL)
    text(s, "ТЭЦ", 0.98, 4.87, 0.82, 0.30, 12, MUTED, True, PP_ALIGN.CENTER)
    line(s, 1.76, 4.35, 2.55, 4.35, TEAL, 8)
    line(s, 2.55, 3.12, 2.55, 5.43, TEAL, 8)
    line(s, 2.55, 3.12, 7.79, 3.12, TEAL, 8)
    line(s, 2.55, 5.43, 7.79, 5.43, "327A86", 8)
    for x, y1, y2, label, color in [
        (3.80, 3.12, 2.25, "А", TEAL),
        (6.37, 3.12, 2.25, "Б", TEAL),
        (4.77, 5.43, 5.83, "В", "327A86"),
        (7.00, 5.43, 5.83, "Г", "327A86"),
    ]:
        line(s, x, y1, x, y2 + (0.30 if y2 < y1 else 0), color, 3.8)
        rect(s, x - 0.34, y2, 0.68, 0.59, PALE, radius=True, line="99BFBB")
        text(s, label, x - 0.34, y2 + 0.10, 0.68, 0.33,
             15, INK, True, PP_ALIGN.CENTER)
    text(s, "2 КОРИДОРА  ·  4 ОТВОДА", 3.18, 4.10, 4.30, 0.30,
         13, MUTED, True, PP_ALIGN.CENTER)
    steps = [("01", "Разделить цели", "Соседние здания объединяем в проверяемые секции."),
             ("02", "Найти коридор", "Ищем длинный допустимый ствол с несколькими врезками."),
             ("03", "Проверить будущее", "Сравниваем продолжения и отдельное подключение."),
             ("04", "Принять сеть", "Пересчитываем расход, диаметр, камеры и всю цену.")]
    for i, (n, title, body) in enumerate(steps):
        y = 1.99 + i * 1.13
        text(s, n, 8.92, y + 0.06, 0.52, 0.30, 12, TEAL, True)
        text(s, title, 9.45, y, 3.08, 0.42, 17, INK, True)
        text(s, body, 9.45, y + 0.39, 3.08, 0.62, 13, MUTED,
             valign=MSO_ANCHOR.TOP)

    # 5 — validation
    s = slide_base(prs, 5, "Надёжность", "Поиск предлагает. Валидатор решает.",
                   "Ни одна отклонённая геометрия не попадает в итоговый вариант.")
    checks = [
        ("01", "Здания и отступы", "Собственный ОКС, чужие контуры и габариты сети"),
        ("02", "Пересечения", "Прямые спецпроходы и обязательное продолжение"),
        ("03", "Топология", "Единое дерево, узлы и допустимые точки врезки"),
        ("04", "Инженерия", "Расход, минимальный диаметр, длина и стоимость"),
    ]
    for i, (n, title, body) in enumerate(checks):
        x = 0.75 + (i % 2) * 6.05
        y = 2.02 + (i // 2) * 2.12
        rect(s, x, y, 5.78, 1.83, WHITE, radius=True, line="D9E7E4")
        circle(s, x + 0.27, y + 0.39, 0.74, TEAL)
        text(s, n, x + 0.27, y + 0.58, 0.74, 0.28, 15, WHITE, True, PP_ALIGN.CENTER)
        text(s, title, x + 1.21, y + 0.34, 4.27, 0.44, 19, INK, True)
        text(s, body, x + 1.21, y + 0.83, 4.20, 0.66, 14.3, MUTED)
    rect(s, 0.75, 6.43, 11.83, 0.42, PALE, radius=True)
    text(s, "Строгая геометрия остаётся обязательной даже при неполном поиске альтернатив.",
         1.02, 6.48, 11.25, 0.29, 13.8, INK, True)

    # 6 — evidence map
    s = slide_base(prs, 6, "Контрольный прогон", "Проверенная геометрия на карте",
                   "Полный API v28 · 29.09.2026 · частичный результат, штраф включён")
    rect(s, 0.73, 2.02, 8.24, 4.56, WHITE, radius=True, line="D9E7E4")
    s.shapes.add_picture(str(evidence_map), Inches(0.77), Inches(2.06),
                         width=Inches(8.16), height=Inches(4.47))
    rect(s, 0.88, 5.99, 7.83, 0.42, WHITE, radius=True)
    text(s, "Итоговая сеть — оранжевым  ·  карта © OpenStreetMap contributors",
         1.05, 6.05, 7.48, 0.25, 11.5, INK, True)
    rect(s, 9.24, 2.02, 3.34, 4.56, WHITE, radius=True, line="D9E7E4")
    text(s, f"{maximum['connectedCount']}/{report['targetCount']}",
         9.54, 2.36, 2.70, 0.90, 43, TEAL, True)
    text(s, "подключено", 9.56, 3.29, 2.70, 0.34, 17, INK, True)
    rect(s, 9.56, 3.89, 2.66, 0.018, "D9E7E4")
    text(s, "3 цели без маршрута", 9.56, 4.18, 2.66, 0.37, 15, INK, True)
    text(s, "№ 2, 5 и 10", 9.56, 4.65, 2.66, 0.33, 16, CORAL, True)
    rect(s, 9.56, 5.48, 2.66, 0.66, PALE, radius=True)
    text(s, "PARTIAL · штраф учтён", 9.69, 5.67, 2.42, 0.27,
         12.4, INK, True)

    # 7 — cost and status semantics
    s = slide_base(prs, 7, "Экономика", "Стоимость всей сети — с ценой неполноты",
                   "Исправлен пропуск штрафа для целей с незавершённым поиском.")
    rect(s, 0.75, 2.02, 11.83, 1.45, DARK, radius=True)
    text(s, "672,373 млн ₽", 1.06, 2.22, 7.80, 0.79, 42, WHITE, True)
    text(s, "итоговая стоимость · 14/17", 8.34, 2.57, 3.86, 0.37,
         16, MINT, True, PP_ALIGN.RIGHT)
    rect(s, 0.75, 3.77, 5.74, 1.38, WHITE, radius=True, line="D9E7E4")
    rect(s, 6.78, 3.77, 5.80, 1.38, WHITE, radius=True, line="D9E7E4")
    text(s, "333,378 млн ₽", 1.04, 3.94, 4.90, 0.55, 29, TEAL, True)
    text(s, "строительство сети", 1.05, 4.55, 4.98, 0.34, 15.5, INK)
    text(s, "338,995 млн ₽", 7.07, 3.94, 4.90, 0.55, 29, CORAL, True)
    text(s, "штраф за 3 неподключённые цели", 7.08, 4.55, 5.11, 0.34,
         15.5, INK)
    rect(s, 0.75, 5.55, 5.87, 0.18, TEAL)
    rect(s, 6.62, 5.55, 5.96, 0.18, CORAL)
    text(s, "Строительство оплачивается по общей сети один раз; штраф — по каждой непокрытой цели.",
         0.78, 5.97, 11.76, 0.43, 16, INK, True)
    text(s, "Штраф цели: 100 млн ₽ + 0,5 млн ₽ × расход; оценка включает стоимость и новую длину сети.",
         0.78, 6.47, 11.75, 0.34, 12.5, MUTED)

    # 8 — exact own-building constraint behind the three missing targets
    s = slide_base(prs, 8, "Граница применимости", "Почему три цели остались вне сети",
                   "Ближайший прямой ввод повторно пересекает собственный ОКС до нужного отступа.")
    rect(s, 0.75, 2.01, 5.66, 3.51, WHITE, radius=True, line="D9E7E4")
    text(s, "ПОЧЕМУ ПРЯМОЙ ВВОД НЕ ПРОХОДИТ", 1.01, 2.20, 5.14, 0.25,
         11.4, TEAL, True)
    text(s, "Условная схема, не в масштабе", 1.02, 2.50, 5.07, 0.22,
         10.5, MUTED)
    # Simplified own-building geometry: a straight exit crosses a second wing.
    rect(s, 1.15, 2.83, 0.77, 2.10, "91ACB3")
    rect(s, 1.15, 2.83, 3.70, 0.61, "91ACB3")
    rect(s, 4.08, 2.83, 0.77, 2.10, "91ACB3")
    text(s, "свой ОКС", 1.20, 3.00, 3.55, 0.27, 13, WHITE, True,
         PP_ALIGN.CENTER)
    text(s, "двор", 2.29, 3.68, 1.39, 0.30, 14, MUTED, True,
         PP_ALIGN.CENTER)
    circle(s, 1.35, 4.01, 0.34, CORAL)
    line(s, 1.69, 4.18, 5.52, 4.18, CORAL, 5)
    circle(s, 4.31, 4.02, 0.31, WHITE, CORAL)
    text(s, "точка ввода", 1.11, 5.08, 1.81, 0.25, 11.2, MUTED)
    text(s, "повторный вход", 3.48, 5.08, 2.55, 0.25, 11.2, CORAL, True)
    blockers = [
        ("ЦЕЛЬ 2 · ОКС 77", "0,59 м", "5,255 м", "повторный вход"),
        ("ЦЕЛЬ 5 · ОКС 72", "0,78 м", "5,300 м", "замкнутый двор"),
        ("ЦЕЛЬ 10 · ОКС 88", "3,39 м", "5,300 м", "повторный вход"),
    ]
    for i, (label, gap, clearance, reason) in enumerate(blockers):
        y = 2.01 + i * 1.18
        rect(s, 6.68, y, 5.90, 1.07, WHITE, radius=True, line="D9E7E4")
        text(s, label, 6.94, y + 0.15, 2.25, 0.25, 11.2, CORAL, True)
        text(s, gap, 6.94, y + 0.48, 2.08, 0.42, 24, INK, True)
        text(s, f"нужно {clearance}", 9.12, y + 0.25, 3.12, 0.30,
             14.5, INK, True)
        text(s, reason, 9.12, y + 0.61, 3.12, 0.27, 12, MUTED)
    rect(s, 0.75, 5.78, 11.83, 0.54, PALE, radius=True)
    text(s, "§2.2: ближайшая граница и прямой ввод  ·  табл. 2: повторный вход в ОКС запрещён",
         0.98, 5.91, 11.38, 0.27, 15, INK, True)
    text(s, "Другой фасад свободен, но дальше ближайшей границы. §2.9 обещает 17/17 — нужно разъяснение; это не доказательство невозможности.",
         0.83, 6.49, 11.75, 0.38, 12.1, MUTED)

    # 9 — honest handoff
    s = slide_base(prs, 9, "Статус и сдача", "Сервис работает. Результат проверен.",
                   "Полный API-прогон, экспорт и экономика сверены с данными.", dark=True)
    text(s, "14/17", 0.78, 2.22, 5.16, 1.22, 61, MINT, True)
    text(s, "подключённых зданий", 0.80, 3.48, 5.14, 0.49, 21, WHITE, True)
    text(s, "PARTIAL · полный API v28", 0.80, 4.14, 5.11, 0.36,
         14, "B7D3D1", True)
    rect(s, 6.18, 2.22, 0.025, 2.64, "46616A")
    text(s, "Проверено", 6.60, 2.29, 5.30, 0.38, 18, MINT, True)
    text(s, "Геометрия сети, 144 входных объекта, экспорт и начисление штрафа.",
         6.60, 2.73, 5.52, 0.75, 19, WHITE)
    text(s, "Открыто", 6.60, 3.71, 5.20, 0.34, 18, CORAL, True)
    text(s, "Цели 2, 5 и 10; критерий 17/17 пока не подтверждён.",
         6.60, 4.12, 5.50, 0.70, 18, WHITE)
    rect(s, 0.75, 5.31, 11.83, 1.05, "173B45", radius=True)
    text(s, "Следующий шаг", 1.04, 5.50, 2.46, 0.29, 12, MINT, True)
    text(s, "Разъяснение по выходу из ОКС → проверка полного подключения",
         1.04, 5.86, 11.08, 0.32, 18, WHITE, True)

    path = OUT / "heat-network-routing.pptx"
    prs.save(path)
    print(path)
    assert len(prs.slides) == TOTAL_SLIDES
    print(f"slides={len(prs.slides)} illustration={map_path}")


if __name__ == "__main__":
    make()
