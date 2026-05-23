<template>
  <div class="bg-white rounded p-6">
    <h2 class="text-xl font-semibold mb-2">欢迎回来，{{ user?.nickname || user?.username }}</h2>
    <p class="text-gray-600">M1 骨架已跑通。文章/分类/评论等模块将由 M2~M4 添加。</p>
  </div>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/user'

const { user } = storeToRefs(useUserStore())
</script>
