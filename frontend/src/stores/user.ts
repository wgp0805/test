import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, getMe, logout as apiLogout, type LoginPayload } from '@/api/auth'
import type { User } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>('')
  const user = ref<User | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  async function login(payload: LoginPayload) {
    const res = await apiLogin(payload)
    token.value = res.token
    user.value = res.user
    localStorage.setItem('blog_token', res.token)
  }

  async function fetchMe() {
    const me = await getMe()
    user.value = me
    return me
  }

  async function logout() {
    try {
      await apiLogout()
    } catch {
      /* 后端无状态，失败也忽略 */
    }
    token.value = ''
    user.value = null
    localStorage.removeItem('blog_token')
    localStorage.removeItem('blog_user')
  }

  return { token, user, isLoggedIn, login, fetchMe, logout }
}, {
  persist: {
    key: 'blog_user',
    pick: ['token', 'user'],
  },
})
