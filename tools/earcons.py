#!/usr/bin/env python3
"""Генерирует earcon приложения (MOB-4) в app/src/main/res/raw/earcon_<имя>.wav.

Только стандартная библиотека: синусоидальные тоны с плавными краями, 44,1 кГц, моно, 16 бит.
Каждый звук короче 300 мс, кроме сигнала опасности, и узнаётся на слух по высоте и рисунку:

    tap           касание: короткий щелчок
    listen_start  начало записи «слушаю»: два тона вверх
    listen_end    конец записи: два тона вниз
    double_tap    двойное касание: два коротких щелчка
    error         ошибка: низкий длинный тон
    unavailable   «недоступно»: короткий низкий тон
    hazard        перед предупреждением об опасности: три высоких тона

Запуск из корня репозитория: python3 tools/earcons.py [--out app/src/main/res/raw]
"""

import argparse
import math
import struct
import wave
from pathlib import Path

RATE = 44100
AMPLITUDE = 0.6
FADE_MS = 5

# Звук - последовательность (частота Гц, длительность мс); частота 0 - пауза
EARCONS = {
    "tap": [(1200, 40)],
    "listen_start": [(660, 70), (0, 20), (990, 90)],
    "listen_end": [(990, 70), (0, 20), (660, 90)],
    "double_tap": [(1200, 35), (0, 50), (1200, 35)],
    "error": [(220, 300)],
    "unavailable": [(330, 80)],
    "hazard": [(1760, 90), (0, 50), (1760, 90), (0, 50), (1760, 90)],
}


def tone(freq: float, ms: int) -> list[float]:
    """Тон или пауза с плавным нарастанием и спадом, чтобы не было щелчков на краях."""
    count = RATE * ms // 1000
    if freq == 0:
        return [0.0] * count
    fade = RATE * FADE_MS // 1000
    samples = []
    for i in range(count):
        gain = min(1.0, i / fade, (count - 1 - i) / fade)
        samples.append(AMPLITUDE * gain * math.sin(2 * math.pi * freq * i / RATE))
    return samples


def render(parts: list[tuple[float, int]]) -> bytes:
    samples = [s for freq, ms in parts for s in tone(freq, ms)]
    return b"".join(struct.pack("<h", round(s * 32767)) for s in samples)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("app/src/main/res/raw"), help="каталог для WAV")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name, parts in EARCONS.items():
        path = args.out / f"earcon_{name}.wav"
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(RATE)
            out.writeframes(render(parts))
        print(f"earcon: {path}")


if __name__ == "__main__":
    main()
