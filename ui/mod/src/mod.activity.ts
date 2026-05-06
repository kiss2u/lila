import ApexCharts from 'apexcharts';

import { currentTheme } from 'lib/device';

const light = currentTheme() === 'light';
const gridC = light ? '#dddddd' : '#333333';

const conf = (title: string, xaxis: Date[]) => ({
  title: {
    text: title,
  },
  theme: {
    mode: light ? 'light' : 'dark',
  },
  chart: {
    type: 'line',
    zoom: {
      enabled: false,
    },
    animations: {
      enabled: false,
    },
    background: 'transparent',
  },
  // series: data.reports.series,
  xaxis: {
    type: 'datetime',
    categories: xaxis,
  },
  yaxis: {
    opposite: true,
    min: 0,
  },
  legend: {
    position: 'top',
  },
  stroke: {
    width: xaxis.length > 50 ? 1 : 2,
  },
  grid: {
    borderColor: gridC,
  },
});

export function initModule({ data }: { data: any }): void {
  queues(data);
}

function queues(data: any) {
  const $grid = $('.chart-grid');
  data.rooms.forEach((room: any) => {
    const cfg = merge(
      {
        ...conf(room.name, data.common.xaxis),
        series: room.series.map((s: any) => ({
          ...s,
          name: `Score: ${s.name}`,
        })),
      },
      {
        chart: {
          type: 'bar',
          stacked: true,
        },
        colors: ['#03A9F4', '#4CAF50', '#F9CE1D', '#FF9800'],
      },
    );
    new ApexCharts($('<div>').appendTo($grid)[0], cfg).render();
  });
}

function merge(base: any, extend: any): void {
  for (const key in extend) {
    if (!extend.hasOwnProperty(key) || key === '__proto__' || key === 'constructor') continue;
    if (base.hasOwnProperty(key) && isObject(base[key]) && isObject(extend[key]))
      merge(base[key], extend[key]);
    else base[key] = extend[key];
  }
  return base;
}

function isObject(o: unknown): boolean {
  return typeof o === 'object';
}
