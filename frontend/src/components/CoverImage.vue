<script setup lang="ts">
import { toRef } from 'vue';
import { useCoverImage } from '../composables/useCoverImage';
const props = defineProps<{ url: string; alt?: string }>();
const image = useCoverImage(toRef(props, 'url'));
</script>
<template><img v-if="image" class="cover" :src="image" :alt="alt || ''"><span v-else class="cover placeholder" aria-hidden="true">♫</span></template>
