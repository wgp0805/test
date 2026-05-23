import axios, { type AxiosResponse } from 'axios'
import { ElMessage, ElNotification } from 'element-plus'
import type { ApiResult } from '@/types'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('blog_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response: AxiosResponse<ApiResult<unknown>>) => {
    const body = response.data
    if (body.code === 0) {
      return body.data as never
    }
    if (body.code === 1401) {
      localStorage.removeItem('blog_token')
      localStorage.removeItem('blog_user')
      ElMessage.warning('登录已过期，请重新登录')
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.href = `/admin/login?redirect=${redirect}`
      return Promise.reject(body)
    }
    if (body.code === 1429) {
      ElMessage.warning(body.message || '操作过于频繁')
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(body)
  },
  (error) => {
    ElNotification.error({ title: '网络错误', message: '系统繁忙，请稍后重试' })
    return Promise.reject(error)
  },
)

export default request
