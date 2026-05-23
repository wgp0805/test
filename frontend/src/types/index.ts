export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface User {
  id: number
  username: string
  nickname: string
  avatar?: string
  email?: string
  bio?: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: User
}
