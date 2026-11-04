# Shared HMAC Authentication Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将本地备份系统与线上医工宝的内部接口认证简化为单一共享密钥 HMAC，不在 HTTP 中传输密钥明文。

**Architecture:** 双方通过同名环境变量 `BACKUP_SHARED_HMAC_SECRET` 读取共享密钥。客户端签名方法、路径、查询、请求体哈希、时间戳、Nonce 和幂等键；服务端使用同一密钥验签并持久化 Nonce 防重放。删除 Key ID、Scope、Bearer/NONE 和机器身份/密钥元数据依赖。

**Tech Stack:** Java 21, Spring Boot 3.2, MyBatis-Plus, MySQL 8, JUnit 5.

**Spec:** 本对话中用户确认“按推荐方案实施”。

## Global Constraints

- 保留 HTTP 通信，密钥不得进入请求、日志或数据库。
- 保留时间戳检查、Nonce 防重放、请求体完整性和写请求幂等键。
- 生产环境不允许无认证或 Bearer 旁路。
- 新旧数据库都必须可以通过初始化/迁移脚本进入新状态。

## Review Focus

- GET 查询参数顺序不同时签名必须一致。
- POST 请求体被修改后必须验签失败。
- 时间戳过期、Nonce 重复、密钥缺失必须 fail closed。
- 客户端和服务端的 canonical request 字节必须完全一致。
- 旧配置键不得继续出现在前端配置页或运行时读取路径。

---

### Task 1: 共享 HMAC 签名契约

- [x] 先修改双方签名/验签测试，验证旧 Key ID/Scope 契约不再满足新断言。
- [x] 客户端改为只发送 Timestamp、Nonce、Content SHA256、Signature 和写请求幂等键。
- [x] 服务端改为使用单一共享密钥验签，保留时间窗口与 Nonce 防重放。

### Task 2: 配置与数据库清理

- [x] 本地运行时改为固定读取 `BACKUP_SHARED_HMAC_SECRET`。
- [x] 删除数据库中 auth-mode、key-id、secret-ref、bearer-token-ref、scope 配置。
- [x] 新增幂等迁移脚本并同步全量初始化脚本、`.env.example` 和部署文档。

### Task 3: 全链路验证与自审

- [x] 运行线上备份模块专项测试和编译。
- [x] 运行本地后端完整测试、打包和前端构建。
- [x] 执行初始化/迁移 SQL 验证，搜索并清除所有旧运行时配置引用。
