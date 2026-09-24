import React, { useEffect, useRef, useState } from "react";
import { createChart, IChartApi, ISeriesApi } from "lightweight-charts";
import { MarketTick } from "../types/trading";
import { TrendingUp, BarChart2, Maximize2, Minimize2 } from "lucide-react";

interface ChartSectionProps {
  ticks: Record<string, MarketTick>;
  isExpanded?: boolean;
  onToggleExpand?: () => void;
}

export const ChartSection: React.FC<ChartSectionProps> = ({
  ticks,
  isExpanded = false,
  onToggleExpand,
}) => {
  const [selectedSymbol, setSelectedSymbol] = useState<string>("AAPL");
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const smaSeriesRef = useRef<ISeriesApi<"Line"> | null>(null);
  const currentCandleRef = useRef<{
    time: number;
    open: number;
    high: number;
    low: number;
    close: number;
  } | null>(null);
  const initializedSymbolRef = useRef<string>("");

  // Realistic seed data anchored to actual current market price
  const generateInitialData = (basePrice: number) => {
    const data = [];
    const now = Math.floor(Date.now() / 60000) * 60; // 1-minute aligned
    let price = basePrice;
    for (let i = 60; i >= 0; i--) {
      const time = (now - i * 60) as any;
      const variation = (Math.random() - 0.49) * (basePrice * 0.001);
      const open = price;
      const close = price + variation;
      const high = Math.max(open, close) + Math.random() * (basePrice * 0.0005);
      const low = Math.min(open, close) - Math.random() * (basePrice * 0.0005);
      price = close;
      data.push({
        time,
        open: parseFloat(open.toFixed(2)),
        high: parseFloat(high.toFixed(2)),
        low: parseFloat(low.toFixed(2)),
        close: parseFloat(close.toFixed(2)),
      });
    }
    // Anchor last candle to exact basePrice
    data[data.length - 1].close = basePrice;
    return data;
  };

  useEffect(() => {
    if (!chartContainerRef.current) return;

    // Initialize chart
    const chart = createChart(chartContainerRef.current, {
      width: chartContainerRef.current.clientWidth,
      height: isExpanded ? 580 : 420,
      layout: {
        background: { color: "#151922" },
        textColor: "#848E9C",
      },
      grid: {
        vertLines: { color: "#1E2430" },
        horzLines: { color: "#1E2430" },
      },
      crosshair: {
        mode: 1,
      },
      timeScale: {
        borderColor: "#232936",
        timeVisible: true,
        secondsVisible: false,
      },
    });

    const candlestickSeries = chart.addCandlestickSeries({
      upColor: "#00C076",
      downColor: "#FF3B69",
      borderVisible: false,
      wickUpColor: "#00C076",
      wickDownColor: "#FF3B69",
    });

    const smaSeries = chart.addLineSeries({
      color: "#2962FF",
      lineWidth: 2,
    });

    const latestPrice = ticks[selectedSymbol]?.price;
    const basePrice = latestPrice || (selectedSymbol === "AAPL" ? 336.87 : 499.06);
    const initialData = generateInitialData(basePrice);
    candlestickSeries.setData(initialData);

    // Calculate initial SMA
    const smaData = initialData.map((d, index, arr) => {
      const start = Math.max(0, index - 9);
      const windowSlice = arr.slice(start, index + 1);
      const avg = windowSlice.reduce((sum, item) => sum + item.close, 0) / windowSlice.length;
      return { time: d.time, value: parseFloat(avg.toFixed(2)) };
    });
    smaSeries.setData(smaData);

    chartRef.current = chart;
    seriesRef.current = candlestickSeries;
    smaSeriesRef.current = smaSeries;
    initializedSymbolRef.current = selectedSymbol;
    currentCandleRef.current = null;

    const handleResize = () => {
      if (chartContainerRef.current && chart) {
        chart.applyOptions({ width: chartContainerRef.current.clientWidth });
      }
    };

    window.addEventListener("resize", handleResize);

    return () => {
      window.removeEventListener("resize", handleResize);
      chart.remove();
    };
  }, [selectedSymbol]);

  // Handle dynamic resize when expanding/collapsing container
  useEffect(() => {
    if (chartRef.current && chartContainerRef.current) {
      const timer = setTimeout(() => {
        if (chartRef.current && chartContainerRef.current) {
          chartRef.current.applyOptions({
            width: chartContainerRef.current.clientWidth,
            height: isExpanded ? 580 : 420,
          });
        }
      }, 50);
      return () => clearTimeout(timer);
    }
  }, [isExpanded]);

  // Update chart when real-time tick arrives
  useEffect(() => {
    const tick = ticks[selectedSymbol];
    if (!tick || !seriesRef.current) return;

    // If the chart was mounted before the first tick arrived, re-seed once to snap to real price
    if (initializedSymbolRef.current !== selectedSymbol) {
      initializedSymbolRef.current = selectedSymbol;
      const initialData = generateInitialData(tick.price);
      seriesRef.current.setData(initialData);
      if (smaSeriesRef.current) {
        const smaData = initialData.map((d, index, arr) => {
          const start = Math.max(0, index - 9);
          const windowSlice = arr.slice(start, index + 1);
          const avg = windowSlice.reduce((sum, item) => sum + item.close, 0) / windowSlice.length;
          return { time: d.time, value: parseFloat(avg.toFixed(2)) };
        });
        smaSeriesRef.current.setData(smaData);
      }
    }

    const candleTime = (Math.floor(tick.time / 60000) * 60) as any;
    if (!currentCandleRef.current || currentCandleRef.current.time !== candleTime) {
      currentCandleRef.current = {
        time: candleTime,
        open: tick.price,
        high: tick.price,
        low: tick.price,
        close: tick.price,
      };
    } else {
      currentCandleRef.current.high = Math.max(currentCandleRef.current.high, tick.price);
      currentCandleRef.current.low = Math.min(currentCandleRef.current.low, tick.price);
      currentCandleRef.current.close = tick.price;
    }

    try {
      seriesRef.current.update(currentCandleRef.current as any);
    } catch (e) {
      // time ordering handle
    }
  }, [ticks, selectedSymbol]);

  const currentTick = ticks[selectedSymbol];
  const displayPrice = currentTick ? currentTick.price : selectedSymbol === "AAPL" ? 336.87 : 499.06;

  return (
    <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-4 pb-2 border-b border-[#232936]">
        <div className="flex items-center space-x-3">
          <div className="p-2 bg-emerald-500/10 border border-emerald-500/20 rounded-lg text-emerald-400">
            <BarChart2 className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <span className="text-base font-bold text-white">{selectedSymbol}/USD</span>
              <span className="text-xs bg-[#0B0E14] text-emerald-400 font-mono px-2 py-0.5 rounded border border-[#232936] flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                Redis Live
              </span>
            </div>
            <div className="text-xl font-bold font-mono text-white flex items-center gap-2 mt-0.5">
              ${displayPrice.toFixed(2)}
              <span className="text-xs font-normal text-emerald-400 flex items-center font-sans">
                <TrendingUp className="w-3.5 h-3.5 mr-0.5" /> Live Ingestion
              </span>
            </div>
          </div>
        </div>

        <div className="flex items-center space-x-3">
          {/* Symbol Selector Chips */}
          <div className="flex items-center space-x-2 bg-[#0B0E14] p-1 rounded-lg border border-[#232936]">
            {["AAPL", "MSFT"].map((sym) => (
              <button
                key={sym}
                onClick={() => setSelectedSymbol(sym)}
                className={`px-3 py-1 text-xs font-mono font-semibold rounded transition ${
                  selectedSymbol === sym
                    ? "bg-blue-600 text-white shadow-sm"
                    : "text-gray-400 hover:text-white"
                }`}
              >
                {sym}
              </button>
            ))}
          </div>

          {/* Expand/Collapse Toggle Button */}
          {onToggleExpand && (
            <button
              onClick={onToggleExpand}
              title={isExpanded ? "Collapse to standard split view" : "Expand to full width view"}
              className={`p-1.5 rounded-lg border text-xs font-mono flex items-center gap-1.5 transition ${
                isExpanded
                  ? "bg-blue-600/20 text-blue-400 border-blue-500/40 hover:bg-blue-600/30"
                  : "bg-[#0B0E14] text-gray-400 border-[#232936] hover:text-white hover:border-gray-600"
              }`}
            >
              {isExpanded ? (
                <>
                  <Minimize2 className="w-4 h-4" />
                  <span className="hidden sm:inline">Minimize</span>
                </>
              ) : (
                <>
                  <Maximize2 className="w-4 h-4" />
                  <span className="hidden sm:inline">Expand</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* Chart Canvas Container */}
      <div ref={chartContainerRef} className="w-full relative rounded-lg overflow-hidden" />
      <div className="flex items-center justify-between text-xs text-gray-500 font-mono pt-1">
        <span>Engine: TradingView Lightweight Charts (Apache 2.0 Canvas)</span>
        <span className="flex items-center gap-2">
          <span className="inline-block w-2.5 h-0.5 bg-blue-500 rounded" /> SMA (10)
        </span>
      </div>
    </div>
  );
};
