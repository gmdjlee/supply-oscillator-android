"""
크로스 검증 스크립트: Python ↔ Kotlin 결과 비교

동일한 고정 입력 데이터를 사용하여 Python(pandas)과 Kotlin 구현의
계산 결과가 일치하는지 검증합니다.

이 스크립트의 출력 값을 Kotlin 테스트의 기대값으로 사용합니다.

실행: python cross_validate.py
"""

import pandas as pd
import numpy as np
import json

# ============================================================
# 고정 입력 데이터 (Kotlin CalcOscillatorUseCaseTest 와 동일)
# ============================================================
MCAP = 100_000_000_000_000  # 100조

FOREIGN_BUYS = [
    5_000_000_000, -3_000_000_000, 8_000_000_000, -1_000_000_000, 4_000_000_000,
    -6_000_000_000, 2_000_000_000, 7_000_000_000, -2_000_000_000, 3_000_000_000
]

INST_BUYS = [
    2_000_000_000, 4_000_000_000, -5_000_000_000, 3_000_000_000, 1_000_000_000,
    6_000_000_000, -3_000_000_000, -1_000_000_000, 5_000_000_000, 2_000_000_000
]

# EMA 파라미터 (엑셀과 동일)
EMA_FAST = 12
EMA_SLOW = 26
EMA_SIGNAL = 9
ROLLING_WINDOW = 5


def main():
    print("=" * 70)
    print("수급 오실레이터 크로스 검증 (Python pandas → Kotlin 기대값 생성)")
    print("=" * 70)

    # DataFrame 생성
    df = pd.DataFrame({
        'date': [f'2024010{i+1}' for i in range(10)],
        'market_cap': [MCAP] * 10,
        'foreign': FOREIGN_BUYS,
        'inst': INST_BUYS,
    })

    # Step 2: 5일 누적 (pandas rolling - 엑셀 동일 로직)
    df['foreign_5d'] = df['foreign'].rolling(window=ROLLING_WINDOW, min_periods=1).sum()
    df['inst_5d'] = df['inst'].rolling(window=ROLLING_WINDOW, min_periods=1).sum()

    print("\n--- Step 2: 5일 누적 순매수 ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: 외국인5d={row['foreign_5d']:,.0f}, 기관5d={row['inst_5d']:,.0f}")

    # Step 3: 수급비율
    df['supply_ratio'] = (df['foreign_5d'] + df['inst_5d']) / df['market_cap']

    print("\n--- Step 3: 수급 비율 ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: ratio={row['supply_ratio']:.15e}")

    # Step 4: EMA 12일, 26일
    # pandas ewm(alpha=..., adjust=False) = 엑셀 EMA 수식과 정확히 동일
    df['ema12'] = df['supply_ratio'].ewm(alpha=2/(EMA_FAST+1), adjust=False).mean()
    df['ema26'] = df['supply_ratio'].ewm(alpha=2/(EMA_SLOW+1), adjust=False).mean()

    print("\n--- Step 4: EMA ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: EMA12={row['ema12']:.15e}, EMA26={row['ema26']:.15e}")

    # Step 5: MACD
    df['macd'] = df['ema12'] - df['ema26']

    print("\n--- Step 5: MACD ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: MACD={row['macd']:.15e}")

    # Step 6: 시그널
    df['signal'] = df['macd'].ewm(alpha=2/(EMA_SIGNAL+1), adjust=False).mean()

    print("\n--- Step 6: 시그널 ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: Signal={row['signal']:.15e}")

    # Step 7: 오실레이터
    df['oscillator'] = df['macd'] - df['signal']

    print("\n--- Step 7: 오실레이터 ---")
    for _, row in df.iterrows():
        print(f"  {row['date']}: Osc={row['oscillator']:.15e}")

    # 시가총액 조 변환
    df['mcap_tril'] = df['market_cap'] / 1_000_000_000_000

    # ============================================================
    # JSON 출력 (Kotlin 테스트 기대값으로 사용)
    # ============================================================
    results = []
    for _, row in df.iterrows():
        results.append({
            'date': row['date'],
            'foreign_5d': int(row['foreign_5d']),
            'inst_5d': int(row['inst_5d']),
            'supply_ratio': row['supply_ratio'],
            'ema12': row['ema12'],
            'ema26': row['ema26'],
            'macd': row['macd'],
            'signal': row['signal'],
            'oscillator': row['oscillator'],
            'mcap_tril': row['mcap_tril'],
        })

    print("\n" + "=" * 70)
    print("JSON 출력 (Kotlin 테스트 기대값)")
    print("=" * 70)
    print(json.dumps(results, indent=2))

    # ============================================================
    # 검증: 수동 계산과 pandas 결과 비교
    # ============================================================
    print("\n" + "=" * 70)
    print("자가 검증: 수동 계산 vs pandas 결과")
    print("=" * 70)

    # 수동 EMA 계산
    a12 = 2.0 / (EMA_FAST + 1)
    a26 = 2.0 / (EMA_SLOW + 1)
    a_sig = 2.0 / (EMA_SIGNAL + 1)

    ratios = df['supply_ratio'].tolist()
    manual_ema12 = [ratios[0]]
    manual_ema26 = [ratios[0]]

    for i in range(1, len(ratios)):
        manual_ema12.append(a12 * ratios[i] + (1 - a12) * manual_ema12[-1])
        manual_ema26.append(a26 * ratios[i] + (1 - a26) * manual_ema26[-1])

    manual_macd = [e12 - e26 for e12, e26 in zip(manual_ema12, manual_ema26)]

    manual_signal = [manual_macd[0]]
    for i in range(1, len(manual_macd)):
        manual_signal.append(a_sig * manual_macd[i] + (1 - a_sig) * manual_signal[-1])

    manual_osc = [m - s for m, s in zip(manual_macd, manual_signal)]

    all_match = True
    for i in range(10):
        diff_ema12 = abs(manual_ema12[i] - df['ema12'].iloc[i])
        diff_ema26 = abs(manual_ema26[i] - df['ema26'].iloc[i])
        diff_macd = abs(manual_macd[i] - df['macd'].iloc[i])
        diff_signal = abs(manual_signal[i] - df['signal'].iloc[i])
        diff_osc = abs(manual_osc[i] - df['oscillator'].iloc[i])

        max_diff = max(diff_ema12, diff_ema26, diff_macd, diff_signal, diff_osc)
        status = "✅" if max_diff < 1e-15 else "❌"
        if max_diff >= 1e-15:
            all_match = False

        print(f"  Day {i+1}: max_diff={max_diff:.2e} {status}")

    print(f"\n{'✅ 모든 값 일치!' if all_match else '❌ 불일치 발견!'}")

    # ============================================================
    # EMA α 값 출력 (엑셀 G3 셀 대응)
    # ============================================================
    print(f"\n--- EMA α 파라미터 확인 ---")
    print(f"  시기외12 (α = 2/13): {2/13:.15f}")
    print(f"  시기외26 (α = 2/27): {2/27:.15f}")
    print(f"  시그널   (α = 2/10): {2/10:.15f}")

    return df


if __name__ == '__main__':
    main()
