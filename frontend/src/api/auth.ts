import request from './request'
import type { LoginResponse, User } from '@/types'

export interface LoginPayload {
  username: string
  password: string
}

export function login(payload: LoginPayload) {
  return request.post<unknown, LoginResponse>('/auth/login', payload)
}

export function getMe() {
  return request.get<unknown, User>('/auth/me')
}

export function logout() {
  return request.post<unknown, void>('/auth/logout')
}
