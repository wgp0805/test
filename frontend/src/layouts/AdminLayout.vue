<template>
  <el-container class="h-full">
    <el-header class="flex items-center justify-between bg-white shadow-sm px-6">
      <span class="font-semibold text-lg">博客后台</span>
      <div class="flex items-center gap-3">
        <span class="text-gray-600">{{ user?.nickname || user?.username }}</span>
        <el-button size="small" @click="handleLogout">退出</el-button>
      </div>
    </el-header>
    <el-main class="bg-gray-50">
      <RouterView />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const { user } = storeToRefs(userStore)

async function handleLogout() {
  await userStore.logout()
  router.push('/admin/login')
}
</script>
