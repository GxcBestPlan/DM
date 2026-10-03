<script setup>
import { computed } from 'vue'
import { label, tone } from '../labels'

const props = defineProps({
  map: { type: Object, required: true },
  value: { type: [String, Number], default: '' },
})

const text = computed(() => label(props.map, props.value))
const cls = computed(() => `chip tone-${tone(props.map, props.value)}`)
</script>

<template>
  <span :class="cls">{{ text }}</span>
</template>
