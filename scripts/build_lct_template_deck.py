"""Fill the official LCT 2026 PowerPoint template with the verified project result.

The organizer's template is supplied separately and is never overwritten.
"""

from __future__ import annotations

import argparse
from io import BytesIO
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches, Pt


PURPLE = RGBColor(0x52, 0x09, 0x78)
DARK = RGBColor(0x31, 0x0F, 0x53)
PINK = RGBColor(0xFF, 0x00, 0x53)
LILAC = RGBColor(0x8A, 0x83, 0xD1)
PALE = RGBColor(0xFF, 0xD6, 0xE4)
INK = RGBColor(0x1C, 0x1D, 0x22)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
FONT = "Montserrat"

# Template source slides: mandatory 7-11, then selected content layouts 12-29.
ORDER = [7, 8, 9, 10, 11, 16, 25, 20, 13, 17, 18, 24, 12]


def write(shape, text, size=18, color=INK, bold=False, align=PP_ALIGN.LEFT):
    tf = shape.text_frame
    tf.clear()
    tf.word_wrap = True
    tf.margin_left = Inches(0.02)
    tf.margin_right = Inches(0.02)
    tf.margin_top = Inches(0.01)
    tf.margin_bottom = Inches(0.01)
    for index, line in enumerate(text.split("\n")):
        p = tf.paragraphs[0] if index == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_before = Pt(0)
        p.space_after = Pt(3)
        p.line_spacing = 1.08
        run = p.add_run()
        run.text = line
        run.font.name = FONT
        run.font.size = Pt(size)
        run.font.bold = bold
        run.font.color.rgb = color
    return shape


def label(slide, text, x, y, w, h, size=18, color=INK, bold=False, align=PP_ALIGN.LEFT):
    shape = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    return write(shape, text, size, color, bold, align)


def remove(shape):
    element = shape._element
    element.getparent().remove(element)


def map_picture(slide, path, x, y, w, h):
    # Keep the original aspect ratio; the selected regions have the same ratio.
    return slide.shapes.add_picture(str(path), Inches(x), Inches(y), Inches(w), Inches(h))


def main(template: Path, output: Path, map_path: Path):
    prs = Presentation(template)
    assert len(prs.slides) >= 29, "The supplied LCT template has too few slides"
    original = {i: prs.slides[i - 1] for i in ORDER}
    logo_blob = prs.slides[5].shapes[13].image.blob  # DIT logo, official template slide 6

    # 1 — branded cover, source slide 7.
    s = original[7]
    cover_title, cover_team, cover_subtitle = s.shapes[0], s.shapes[1], s.shapes[2]
    remove(cover_team)
    write(cover_title, "Трассировка\nтепловых сетей", 42, WHITE, True)
    write(cover_subtitle, "Сервис для технологического присоединения новых зданий", 21, WHITE)
    label(s, "БАРЭВЗЭС", 0.48, 0.70, 5.2, 0.52, 30, WHITE, True)
    label(s, "ЛЦТ 2026  /  МОСКВА", 0.48, 2.45, 4.0, 0.38, 15, WHITE, True)
    label(s, "Проверяемая общая сеть: стволы и отводы", 0.48, 3.35, 6.35, 0.47, 17, WHITE)
    s.shapes.add_picture(BytesIO(logo_blob), Inches(0.49), Inches(1.42), width=Inches(2.25))

    # 2 — required overview/team block, source slide 8.
    s = original[8]
    write(s.shapes[6], "О КОМАНДЕ И РЕШЕНИИ", 21, PURPLE, True)
    write(s.shapes[4],
          "Капитан: Лапшин Александр\n"
          "Специальность капитана: фуллстек-разработчик\n"
          "Участников: 4 · РТУ МИРЭА\n"
          "Город: Москва\n"
          "Команда объединила разработку и геоаналитику для задачи городской инфраструктуры.",
          16, INK)
    write(s.shapes[11],
          "Сервис строит варианты общей теплосети для группы новых зданий и показывает их на карте.",
          17, INK)
    write(s.shapes[3],
          "Ствол и отводы выбираются вместе; каждый маршрут проходит отдельную геометрическую проверку.",
          16, INK)
    map_picture(s, map_path, 0.63, 0.45, 5.00, 2.81)
    label(s, "© OpenStreetMap contributors", 0.62, 3.31, 3.2, 0.20, 8, DARK)

    # 3 — required team roster, source slide 9; four real participants, no fake fifth.
    s = original[9]
    source_shapes = list(s.shapes)
    keep_cards = [source_shapes[i] for i in [12, 0, 3, 6]]
    for i in [1, 2, 4, 5, 7, 8, 9, 10, 11, 15, 16, 17, 18, 19, 21, 22]:
        remove(source_shapes[i])
    write(source_shapes[20], "КОМАНДА БАРЭВЗЭС", 18, WHITE, True)
    source_shapes[13].width = Inches(4.7)
    people = [
        ("АЛ", "Александр\nЛапшин", "Капитан · фуллстек", "@Zhaba1337", "+7 977 811 10 17"),
        ("ШМ", "Максим\nШипилов", "Бэкенд · алгоритм", "@max1maln0", "+7 926 677 17 37"),
        ("СК", "Кирилл\nСпицин", "Геоаналитика", "@jdksksjdv", "+7 932 475 13 88"),
        ("СМ", "Миша\nСтепанян", "Фронтенд · карта", "@s0_er", "+7 925 616 78 68"),
    ]
    for card, x, (initials, name, role, nick, phone) in zip(keep_cards, [0.45, 3.58, 6.71, 9.84], people):
        card.left = Inches(x)
        card.width = Inches(2.88)
        badge = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x + 0.86), Inches(2.11), Inches(1.16), Inches(1.16))
        badge.fill.solid(); badge.fill.fore_color.rgb = PALE
        badge.line.fill.background()
        label(s, initials, x + 0.86, 2.45, 1.16, 0.36, 22, PURPLE, True, PP_ALIGN.CENTER)
        label(s, name, x + 0.22, 3.47, 2.48, 0.70, 18, PURPLE, True)
        label(s, role, x + 0.22, 4.35, 2.48, 0.46, 13, PINK, True)
        label(s, f"{nick}\n{phone}\nРТУ МИРЭА\nПрикладная информатика", x + 0.22, 4.91, 2.48, 1.47, 12, INK)

    # 4 — required team story/motivation/challenge, source slide 10.
    s = original[10]
    for i in range(2, 9):
        remove(s.shapes[2])
    label(s, "КОМАНДА И ВЫЗОВЫ", 0.58, 0.51, 4.0, 0.39, 19, WHITE, True)
    label(s, "Как сформировалась команда", 0.61, 1.39, 10.8, 0.38, 18, PURPLE, True)
    label(s, "Четыре студента РТУ МИРЭА объединили разработку, геоаналитику и визуализацию для городской задачи.",
          0.61, 1.89, 10.8, 0.85, 17, INK)
    label(s, "Почему выбрали эту задачу", 0.61, 3.17, 10.8, 0.38, 18, PURPLE, True)
    label(s, "Ближайшая труба не всегда даёт дешёвое подключение всей группы. Этот выбор можно проверить расчётом.",
          0.61, 3.67, 10.8, 0.85, 17, INK)
    label(s, "Главный вызов и как его решили", 0.61, 5.00, 10.8, 0.38, 18, PURPLE, True)
    label(s, "Узкие коридоры и строгие отступы мешают охватить все 17 целей. Мы отделили поиск от геометрической проверки и показываем причины результата 14/17.",
          0.61, 5.51, 10.8, 0.94, 17, INK)

    # 5 — mandatory technical and market summary, source slide 11.
    s = original[11]
    write(s.shapes[4],
          "GeoJSON и PostGIS: здания, сети, дороги, точки подключения.\n"
          "Секции целей, общие стволы и короткие отводы.\n"
          "Поиск коридоров + независимый валидатор геометрии.\n"
          "До трёх вариантов, стоимость и экспорт GeoJSON.",
          17, INK)
    write(s.shapes[5],
          "Инструмент ранней оценки для городских и теплоснабжающих организаций.\n"
          "Сравнение трасс по охвату и полной цене сети.\n"
          "Причины отказов видны до инженерного согласования.\n"
          "Дальше: техусловия, гидравлика и интеграция с ГИС.",
          17, INK)

    # 6 — problem and dataset, source slide 16.
    s = original[16]
    write(s.shapes[14], "ПРОБЛЕМА И ДАННЫЕ", 19, WHITE, True)
    for i, (head, body) in enumerate([
        ("144 объекта", "Конкурсный GeoJSON: здания, дороги и сети в одной системе координат."),
        ("17 целей", "Потребителей нужно подключать как группу, а не по одному зданию."),
        ("85 ОКС", "Контуры сооружений, отступы и спецпроходы ограничивают коридоры."),
        ("Полная цена", "Общий участок оплачивается один раз; штраф за непокрытую цель учитывается."),
    ]):
        write(s.shapes[[6, 8, 10, 12][i]], head, 21, PURPLE, True)
        write(s.shapes[[7, 9, 11, 13][i]], body, 17, INK)
        write(s.shapes[[15, 16, 17, 18][i]], f"0{i + 1}", 18, PINK, True)

    # 7 — five stages of routing, source slide 25.
    s = original[25]
    write(s.shapes[26], "ЭТАПЫ РАСЧЁТА", 19, PURPLE, True)
    stages = [
        (0.42, 1.67, "01  Вход", "GeoJSON и bbox\nв WGS84"),
        (2.92, 5.42, "02  Группы", "Секции соседних\nпотребителей"),
        (5.30, 1.67, "03  Коридор", "Ствол и врезки\nдля всей группы"),
        (7.80, 5.42, "04  Проверка", "Отступы, дерево,\nДУ и пересечения"),
        (10.25, 1.67, "05  Оценка", "Охват, цена сети\nи штраф"),
    ]
    for x, y, head, body in stages:
        label(s, head, x, y, 2.66, 0.41, 17, PURPLE, True)
        label(s, body, x, y + 0.49, 2.66, 0.78, 16, INK)
    label(s, "ЭТАПЫ РАСЧЁТА", 0.58, 0.52, 3.18, 0.37, 19, WHITE, True)

    # 8 — common trunk logic, source slide 20.
    s = original[20]
    s.shapes[0].width = Inches(6.10)
    write(s.shapes[14], "ОБЩИЙ СТВОЛ И ОТВОДЫ", 19, PURPLE, True)
    map_picture(s, map_path, 0.57, 2.15, 5.50, 3.09)
    label(s, "Проверенный вариант v28 · © OpenStreetMap contributors", 0.60, 5.49, 5.45, 0.33, 9, DARK)
    label(s, "Сеть растёт как дерево: следующая цель может подключиться к уже построенному отводу.",
          0.65, 5.97, 5.35, 0.63, 15, PURPLE, True)
    for shape_idx, text in zip([8, 9, 10, 11, 12], [
        "Сначала ищем секции соседних зданий",
        "Прокладываем допустимый общий коридор",
        "Добавляем отводы от уже принятых труб",
        "Проверяем геометрию всей сети заново",
        "Сравниваем полную стоимость и охват",
    ]):
        write(s.shapes[shape_idx], text, 16, PURPLE, True)
    label(s, "ОБЩИЙ СТВОЛ И ОТВОДЫ", 0.58, 0.52, 5.75, 0.37, 19, WHITE, True)

    # 9 — verified map and result, source slide 13.
    s = original[13]
    write(s.shapes[3], "ФАКТ: 14 ИЗ 17 ТОЧЕК", 22, WHITE, True)
    map_picture(s, map_path, 0.75, 1.88, 8.00, 4.50)
    label(s, "© OpenStreetMap contributors", 0.80, 6.49, 7.85, 0.23, 10, DARK)
    label(s, "14/17", 9.12, 2.08, 3.20, 0.83, 43, PINK, True)
    label(s, "подключено в полном API-прогоне v28", 9.14, 3.05, 3.04, 0.89, 19, PURPLE, True)
    label(s, "PARTIAL", 9.14, 4.19, 3.03, 0.43, 21, PINK, True)
    label(s, "Без маршрута: цели 2, 5 и 10.\nШтраф включён в итоговую стоимость.",
          9.14, 4.86, 3.12, 1.13, 16, INK)

    # 10 — verified economics, source slide 17.
    s = original[17]
    write(s.shapes[10], "ЭКОНОМИКА ВАРИАНТА", 19, PURPLE, True)
    for hidx, vidx, didx, head, value, desc in [
        (11, 4, 5, "СТРОИТЕЛЬСТВО", "333,378", "млн ₽\nНовые трубы, камеры и специальные проходы."),
        (12, 6, 7, "ШТРАФ", "338,995", "млн ₽\nЗа 3 цели без маршрута; поиск по ним неполон."),
        (13, 8, 9, "ИТОГО", "672,373", "млн ₽\nПроверенная итоговая стоимость варианта 14/17."),
    ]:
        write(s.shapes[hidx], head, 15, PINK, True)
        write(s.shapes[vidx], value, 31, PURPLE, True)
        write(s.shapes[didx], desc, 18, INK)
    label(s, "Штраф: 100 млн ₽ + 0,5 млн ₽ × расход каждой неподключённой точки.",
          0.73, 6.82, 11.5, 0.25, 10, PURPLE)

    # 11 — precise limitation, source slide 18.
    s = original[18]
    write(s.shapes[14], "ПОЧЕМУ НЕ 17/17", 19, WHITE, True)
    records = [
        (2, "ОКС 77", "0,59 м", "5,255 м", "Повторный вход в собственный ОКС"),
        (5, "ОКС 72", "0,78 м", "5,300 м", "Замкнутый двор и ближайшая граница"),
        (10, "ОКС 88", "3,39 м", "5,300 м", "Повторный вход в собственный ОКС"),
    ]
    for row, (target, oks, clear, required, reason) in enumerate(records):
        title_left, body_left = [2, 4, 6][row], [3, 5, 7][row]
        title_right, body_right = [8, 10, 12][row], [9, 11, 13][row]
        write(s.shapes[title_left], f"ЦЕЛЬ {target} · {oks}", 18, PURPLE, True)
        write(s.shapes[body_left], f"Свободный просвет: {clear}", 16, INK)
        write(s.shapes[title_right], f"НУЖНО {required}", 18, PINK, True)
        write(s.shapes[body_right], reason, 16, INK)
    label(s, "Альтернативный фасад возможен геометрически, но дальше ближайшей границы. Нужна трактовка ТЗ; невозможность не доказана.",
          0.75, 6.64, 11.85, 0.26, 10, DARK)

    # 12 — next steps, source slide 24.
    s = original[24]
    write(s.shapes[15], "СЛЕДУЮЩИЕ ШАГИ", 19, WHITE, True)
    for idx, head, body in [
        (7, "Уточнить правило ввода", "Согласовать с постановщиком ближайшую границу и допустимый альтернативный фасад для целей 2/5/10."),
        (8, "Расширить поиск", "Проверять больше общих коридоров и врезок, не ослабляя независимый валидатор."),
        (9, "Инженерный контур", "Добавить технические условия, гидравлику и интеграцию с городской ГИС."),
    ]:
        write(s.shapes[idx], f"{head}\n{body}", 18, PURPLE if idx == 7 else INK)

    # 13 — closing and links, source slide 12.
    s = original[12]
    write(s.shapes[3], "РЕЗУЛЬТАТ И ДЕМО", 20, PURPLE, True)
    label(s, "14/17", 0.63, 1.82, 5.85, 0.98, 50, PINK, True)
    label(s, "Проверенный частичный результат", 0.65, 2.86, 5.90, 0.70, 21, PURPLE, True)
    label(s, "Работающий веб-сервис · карта · API · экспорт GeoJSON", 0.65, 3.81, 5.90, 0.90, 18, INK)
    label(s, "github.com/Zhaba1337228/hackathon-lct", 0.65, 5.15, 5.97, 0.47, 16, PURPLE, True)
    label(s, "Команда БарэвЗэс · Москва · ЛЦТ 2026", 0.65, 6.13, 5.96, 0.42, 14, DARK)

    # Keep all official masters, backgrounds and organizer marks; only omit
    # instruction/library slides and unused content examples.
    ids = list(prs.slides._sldIdLst)
    for index, element in enumerate(ids, 1):
        prs.slides._sldIdLst.remove(element)
        if index not in ORDER:
            prs.part.drop_rel(element.rId)
    for old_number in ORDER:
        prs.slides._sldIdLst.append(ids[old_number - 1])
    for new_number, old_number in enumerate(ORDER, 1):
        slide = original[old_number]
        for shape in slide.shapes:
            if "Номер слайда" not in shape.name or not getattr(shape, "has_text_frame", False):
                continue
            if shape.text.strip().isdigit():
                write(shape, str(new_number), 10, WHITE if old_number in [9, 11, 13, 16, 24] else PURPLE)

    output.parent.mkdir(parents=True, exist_ok=True)
    prs.save(output)
    print(f"saved={output} slides={len(prs.slides)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", type=Path, default=Path.home() / "Downloads" / "ЛЦТ2026 Шаблон презентации.pptx")
    parser.add_argument("--output", type=Path, default=Path("submission/heat-network-routing.pptx"))
    parser.add_argument("--map", dest="map_path", type=Path, default=Path("submission/assets/map-v28-detail.png"))
    args = parser.parse_args()
    main(args.template, args.output, args.map_path)
