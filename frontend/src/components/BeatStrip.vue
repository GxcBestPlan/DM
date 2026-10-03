<script setup>
import { computed } from 'vue'

const props = defineProps({
  start: { type: String, required: true },
  end: { type: String, required: true },
  marks: { type: Object, default: () => ({}) },
  compact: { type: Boolean, default: false },
  dark: { type: Boolean, default: false },
  /** 0~1：外部动画用，覆盖"按日期推进"的填充状态 */
  progress: { type: Number, default: null },
})

const DOW = ['日', '一', '二', '三', '四', '五', '六']

function fmt(date) {
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}

const today = fmt(new Date())

const days = computed(() => {
  const out = []
  const from = new Date(`${props.start}T00:00:00`)
  const to = new Date(`${props.end}T00:00:00`)
  for (const d = new Date(from); d <= to; d.setDate(d.getDate() + 1)) {
    const iso = fmt(d)
    const dow = d.getDay()
    out.push({
      iso,
      day: d.getDate(),
      dow: DOW[dow],
      weekend: dow === 0 || dow === 6,
      past: iso < today,
      today: iso === today,
      mark: props.marks[iso] || 0,
      filled: false,
    })
  }
  const working = out.filter((d) => !d.weekend)
  working.forEach((d, index) => {
    d.filled =
      props.progress === null
        ? d.past || d.today
        : (index + 1) / Math.max(working.length, 1) <= props.progress + 1e-6
  })
  return out
})

const maxMark = computed(() => Math.max(1, ...days.value.map((d) => d.mark)))
const workingCount = computed(() => days.value.filter((d) => !d.weekend).length)
const filledCount = computed(() => days.value.filter((d) => d.filled).length)
const label = computed(
  () => `迭代 ${props.start} 至 ${props.end}，共 ${workingCount.value} 个工作日，已推进 ${filledCount.value} 天`,
)
</script>

<template>
  <div class="strip" :class="{ compact, dark }" role="img" :aria-label="label">
    <span
      v-for="d in days"
      :key="d.iso"
      class="beat"
      :class="{ weekend: d.weekend, filled: d.filled, today: d.today }"
      :title="`${d.iso} 周${d.dow}${d.mark ? ` · 累计完成 ${d.mark}` : ''}`"
    >
      <template v-if="!d.weekend">
        <span v-if="!compact" class="dow">{{ d.dow }}</span>
        <span class="seg" />
        <span
          v-if="d.mark"
          class="mark"
          :style="{ height: `${Math.max(2, Math.round((d.mark / maxMark) * 10))}px` }"
        />
        <span v-if="!compact" class="dom">{{ d.day }}</span>
      </template>
    </span>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 40px;
}
.beat {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  position: relative;
}
.beat.weekend { flex: 0 0 5px; }

.dow { font-size: 10px; line-height: 1; color: var(--ink-3); }
.dom { font-size: 11px; line-height: 1; color: var(--ink-3); }

.seg {
  display: block;
  width: 100%;
  height: 7px;
  border: 1px solid var(--rule);
  background: var(--panel);
}
.beat.filled .seg { background: var(--ink); border-color: var(--ink); }
.beat.today .seg { background: var(--live); border-color: var(--live); }
.beat.today .dom { color: var(--live); font-weight: 650; }

.mark {
  position: absolute;
  bottom: 14px;
  left: 0;
  right: 0;
  background: var(--done);
  opacity: .8;
}

.strip.compact { height: 8px; gap: 1px; align-items: stretch; }
.strip.compact .beat { justify-content: center; }
.strip.compact .beat.weekend { flex: 0 0 2px; }
.strip.compact .seg { height: 100%; border: 0; border-radius: 1px; background: var(--rule-soft); }
.strip.compact .beat.filled .seg { background: var(--ink-2); }
.strip.compact .beat.today .seg { background: var(--live); box-shadow: none; }

/* 深色底（登录页） */
.strip.dark .dow,
.strip.dark .dom { color: #8ea3b1; }
.strip.dark .seg { background: transparent; border-color: #3a4956; }
.strip.dark .beat.filled .seg { background: #5f7686; border-color: #5f7686; }
.strip.dark .beat.today .seg { background: #35b3c9; border-color: #35b3c9; }
.strip.dark .beat.today .dom { color: #7fd8e8; }
.strip.dark .mark { background: #35b3c9; }
</style>
