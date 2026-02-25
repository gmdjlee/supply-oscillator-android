"""
삼성전자 수급 오실레이터 검증 스크립트

앱의 CalcOscillatorUseCase + StockRepository 로직을 Python으로 1:1 재현하여
사용자 제공 데이터와 비교합니다.

앱 로직:
1. endDate 기준 상장주식수 확보
2. 일별 종가 × 상장주식수 = 시가총액
3. 투자자별 순매수 (외국인합계, 기관합계)
4. 5일 누적 → 수급비율 → EMA12/26 → MACD → Signal → Oscillator
"""

import pandas as pd
import numpy as np
from pykrx import stock
import warnings
warnings.filterwarnings('ignore')

# ============================================================
# 사용자 제공 기대값 (검증 대상)
# ============================================================
EXPECTED = {
    '20241128': {'mcap_tril': 33.1323, 'osc_pct': 0.0000},
    '20241129': {'mcap_tril': 32.3562, 'osc_pct': -0.0028},
    '20241202': {'mcap_tril': 31.9980, 'osc_pct': -0.0090},
    '20241203': {'mcap_tril': 31.9980, 'osc_pct': -0.0131},
    '20241204': {'mcap_tril': 31.6995, 'osc_pct': -0.0109},
    '20241205': {'mcap_tril': 32.0577, 'osc_pct': -0.0051},
    '20241206': {'mcap_tril': 32.2965, 'osc_pct': 0.0055},
    '20241209': {'mcap_tril': 31.8786, 'osc_pct': 0.0151},
    '20241210': {'mcap_tril': 32.2368, 'osc_pct': 0.0208},
    '20241211': {'mcap_tril': 32.2368, 'osc_pct': 0.0238},
    '20241212': {'mcap_tril': 33.3711, 'osc_pct': 0.0262},
    '20241213': {'mcap_tril': 33.4905, 'osc_pct': 0.0229},
    '20241216': {'mcap_tril': 33.1920, 'osc_pct': 0.0153},
    '20241217': {'mcap_tril': 32.3562, 'osc_pct': 0.0070},
    '20241218': {'mcap_tril': 32.7741, 'osc_pct': 0.0036},
    '20241219': {'mcap_tril': 31.6995, 'osc_pct': -0.0107},
    '20241220': {'mcap_tril': 31.6398, 'osc_pct': -0.0243},
    '20241223': {'mcap_tril': 31.9383, 'osc_pct': -0.0287},
    '20241224': {'mcap_tril': 32.4756, 'osc_pct': -0.0237},
}

# ============================================================
# EMA 파라미터 (OscillatorConfig.kt와 동일)
# ============================================================
EMA_FAST = 12
EMA_SLOW = 26
EMA_SIGNAL = 9
ROLLING_WINDOW = 5
MARKET_CAP_DIVISOR = 1_000_000_000_000.0  # 원 → 조

TICKER = '005930'  # 삼성전자

# 분석 기간: DEFAULT_ANALYSIS_DAYS = 365
# endDate를 2024-12-24로 설정 (마지막 데이터 날짜)
END_DATE = '20241224'
# startDate = endDate - 365일 (여유분 포함)
START_DATE = '20231224'


def calc_ema(values, period):
    """
    CalcOscillatorUseCase.calcEma() 와 동일한 EMA 계산

    α = 2 / (period + 1)
    EMA[0] = values[0]
    EMA[i] = α * values[i] + (1 - α) * EMA[i-1]

    pandas.ewm(alpha=α, adjust=False).mean() 과 동일
    """
    if len(values) == 0:
        return []
    alpha = 2.0 / (period + 1)
    result = [values[0]]
    for i in range(1, len(values)):
        ema = alpha * values[i] + (1.0 - alpha) * result[i - 1]
        result.append(ema)
    return result


def main():
    print("=" * 70)
    print("삼성전자 수급 오실레이터 검증")
    print(f"기간: {START_DATE} ~ {END_DATE}")
    print("=" * 70)

    # ============================================================
    # Step 1: 데이터 수집 (StockRepository.getDailyTradingData 재현)
    # ============================================================

    print("\n[1] KRX 데이터 수집 중...")

    # 1-1) 상장주식수: endDate 기준 (앱 로직 동일)
    # StockRepository: krxStock.getMarketCap(date = endDate)
    print(f"  상장주식수 조회 (기준일: {END_DATE})...")
    mcap_df = stock.get_market_cap(END_DATE)
    if TICKER not in mcap_df.index:
        print(f"  ERROR: {TICKER} not found in market cap data for {END_DATE}")
        return
    shares_outstanding = mcap_df.loc[TICKER, '상장주식수']
    print(f"  상장주식수: {shares_outstanding:,}")

    # 1-2) 일별 종가
    # StockRepository: krxStock.getOhlcvByTicker(startDate, endDate, ticker)
    print(f"  일별 종가 조회...")
    ohlcv = stock.get_market_ohlcv(START_DATE, END_DATE, TICKER)
    print(f"  OHLCV 데이터: {len(ohlcv)}일")

    # 1-3) 투자자별 순매수
    # StockRepository: krxStock.getTradingByInvestor(startDate, endDate, ticker, VALUE, NET_BUY)
    print(f"  투자자별 순매수 조회...")
    trading = stock.get_market_trading_value_by_date(START_DATE, END_DATE, TICKER)
    print(f"  거래 데이터: {len(trading)}일")

    # ============================================================
    # Step 2: 데이터 병합 (StockRepository 로직)
    # ============================================================

    print("\n[2] 데이터 병합...")

    # tradingData 기준으로 병합 (앱: tradingData.mapNotNull)
    dates = []
    market_caps = []
    foreign_net_buys = []
    inst_net_buys = []

    for date_idx in trading.index:
        date_str = date_idx.strftime('%Y%m%d')

        # OHLCV에서 종가 찾기
        if date_idx not in ohlcv.index:
            continue

        close_price = ohlcv.loc[date_idx, '종가']
        market_cap = close_price * shares_outstanding

        foreign_net = trading.loc[date_idx, '외국인합계']
        inst_net = trading.loc[date_idx, '기관합계']

        dates.append(date_str)
        market_caps.append(market_cap)
        foreign_net_buys.append(foreign_net)
        inst_net_buys.append(inst_net)

    df = pd.DataFrame({
        'date': dates,
        'market_cap': market_caps,
        'foreign': foreign_net_buys,
        'inst': inst_net_buys,
    })

    print(f"  병합 데이터: {len(df)}일")
    print(f"  기간: {df['date'].iloc[0]} ~ {df['date'].iloc[-1]}")

    # ============================================================
    # Step 3: 오실레이터 계산 (CalcOscillatorUseCase.execute 재현)
    # ============================================================

    print("\n[3] 오실레이터 계산...")

    # Step 2 (CalcOscillator): 5일 누적 순매수
    foreign_list = df['foreign'].tolist()
    inst_list = df['inst'].tolist()
    mcap_list = df['market_cap'].tolist()

    n = len(df)
    foreign_5d = []
    inst_5d = []

    for i in range(n):
        start_idx = max(0, i - ROLLING_WINDOW + 1)
        f_sum = sum(foreign_list[start_idx:i+1])
        i_sum = sum(inst_list[start_idx:i+1])
        foreign_5d.append(f_sum)
        inst_5d.append(i_sum)

    # Step 3 (CalcOscillator): 수급 비율
    supply_ratios = []
    for i in range(n):
        if mcap_list[i] == 0:
            supply_ratios.append(0.0)
        else:
            ratio = (foreign_5d[i] + inst_5d[i]) / mcap_list[i]
            supply_ratios.append(ratio)

    # Step 4: EMA 12, 26
    ema12 = calc_ema(supply_ratios, EMA_FAST)
    ema26 = calc_ema(supply_ratios, EMA_SLOW)

    # Step 5: MACD
    macd = [e12 - e26 for e12, e26 in zip(ema12, ema26)]

    # Step 6: Signal
    signal = calc_ema(macd, EMA_SIGNAL)

    # Step 7: Oscillator
    oscillator = [m - s for m, s in zip(macd, signal)]

    # 시가총액 조 변환
    mcap_tril = [mc / MARKET_CAP_DIVISOR for mc in mcap_list]

    df['mcap_tril'] = mcap_tril
    df['oscillator'] = oscillator
    df['osc_pct'] = [o * 100 for o in oscillator]  # 백분율 변환

    print(f"  계산 완료!")

    # ============================================================
    # Step 4: pandas ewm 으로도 교차 검증
    # ============================================================

    print("\n[4] pandas ewm 교차 검증...")

    sr = pd.Series(supply_ratios)
    pd_ema12 = sr.ewm(alpha=2/(EMA_FAST+1), adjust=False).mean()
    pd_ema26 = sr.ewm(alpha=2/(EMA_SLOW+1), adjust=False).mean()
    pd_macd = pd_ema12 - pd_ema26
    pd_signal = pd_macd.ewm(alpha=2/(EMA_SIGNAL+1), adjust=False).mean()
    pd_osc = pd_macd - pd_signal

    max_diff = max(abs(pd_osc.iloc[i] - oscillator[i]) for i in range(n))
    print(f"  수동 EMA vs pandas ewm 최대 차이: {max_diff:.2e}")
    print(f"  {'✅ 일치' if max_diff < 1e-15 else '❌ 불일치'}")

    # ============================================================
    # Step 5: 사용자 제공 데이터와 비교
    # ============================================================

    print("\n" + "=" * 70)
    print("검증 결과: 앱 계산값 vs 사용자 제공 데이터")
    print("=" * 70)

    print(f"\n{'날짜':<12} {'기대 시총(조)':<14} {'계산 시총(조)':<14} {'시총차이':<12} "
          f"{'기대 오실(%)':<14} {'계산 오실(%)':<14} {'오실차이':<12} {'판정'}")
    print("-" * 110)

    all_match = True
    mcap_matches = 0
    osc_matches = 0
    total = 0

    for i in range(n):
        date = df['date'].iloc[i]
        if date in EXPECTED:
            total += 1
            exp = EXPECTED[date]

            calc_mcap = mcap_tril[i]
            calc_osc = oscillator[i] * 100  # 백분율

            mcap_diff = abs(calc_mcap - exp['mcap_tril'])
            osc_diff = abs(calc_osc - exp['osc_pct'])

            mcap_ok = mcap_diff < 0.01  # 0.01조 허용
            osc_ok = osc_diff < 0.001   # 0.001% 허용

            if mcap_ok:
                mcap_matches += 1
            if osc_ok:
                osc_matches += 1

            status = "✅" if (mcap_ok and osc_ok) else "❌"
            if not (mcap_ok and osc_ok):
                all_match = False

            print(f"{date:<12} {exp['mcap_tril']:<14.4f} {calc_mcap:<14.4f} {mcap_diff:<12.6f} "
                  f"{exp['osc_pct']:<14.4f} {calc_osc:<14.4f} {osc_diff:<12.6f} {status}")

    print("-" * 110)
    print(f"\n총 {total}건 비교:")
    print(f"  시가총액 일치: {mcap_matches}/{total} ({mcap_matches/total*100:.1f}%)")
    print(f"  오실레이터 일치: {osc_matches}/{total} ({osc_matches/total*100:.1f}%)")
    print(f"\n{'✅ 모든 값이 앱 계산 결과와 일치합니다!' if all_match else '❌ 일부 값이 불일치합니다.'}")

    # ============================================================
    # 디버그: 시가총액 역산으로 종가/상장주식수 검증
    # ============================================================

    print("\n" + "=" * 70)
    print("디버그 정보")
    print("=" * 70)

    # 첫 번째 기대 시총에서 역산
    first_date = '20241128'
    first_exp_mcap = EXPECTED[first_date]['mcap_tril'] * MARKET_CAP_DIVISOR
    idx_first = dates.index(first_date) if first_date in dates else -1

    if idx_first >= 0:
        actual_close = ohlcv.loc[pd.Timestamp(f'2024-11-28'), '종가'] if pd.Timestamp('2024-11-28') in ohlcv.index else 'N/A'
        print(f"\n  기준일({END_DATE}) 상장주식수: {shares_outstanding:,}")
        print(f"  2024-11-28 종가: {actual_close:,}")
        print(f"  계산 시가총액: {actual_close * shares_outstanding:,} 원")
        print(f"  계산 시가총액(조): {actual_close * shares_outstanding / MARKET_CAP_DIVISOR:.4f}")
        print(f"  기대 시가총액(조): {EXPECTED[first_date]['mcap_tril']:.4f}")

    # 상세 데이터 출력 (검증 기간)
    print(f"\n--- 검증 기간 상세 데이터 ---")
    print(f"{'날짜':<12} {'종가':<10} {'시총(조)':<12} {'외국인순매수':<18} {'기관순매수':<18} "
          f"{'외5일합':<18} {'기5일합':<18} {'수급비율':<16} {'오실레이터':<16}")

    for i in range(n):
        date = df['date'].iloc[i]
        if date in EXPECTED:
            date_ts = pd.Timestamp(f'{date[:4]}-{date[4:6]}-{date[6:8]}')
            close = ohlcv.loc[date_ts, '종가'] if date_ts in ohlcv.index else 0

            print(f"{date:<12} {close:<10,} {mcap_tril[i]:<12.4f} "
                  f"{foreign_list[i]:<18,.0f} {inst_list[i]:<18,.0f} "
                  f"{foreign_5d[i]:<18,.0f} {inst_5d[i]:<18,.0f} "
                  f"{supply_ratios[i]:<16.10f} {oscillator[i]:<16.10f}")

    return df


if __name__ == '__main__':
    result = main()
