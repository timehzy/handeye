# e2e serve package · 占位

本 package 是未来"可视化 serve 模块"的入口，本次 PR1 只占位，不实现。

## 背景

`framework-improvement-backlog.md` 新增条目"可视化 serve 模块实现（P1）":
- framework 进程内置 HTTP server
- 暴露 `GET /api/scenarios` / `POST /api/run` / `GET /api/runs/<id>` REST endpoints
- WebSocket / SSE 推实时进度
- 前端静态资源（HTML / JS / CSS）

## 架构前置

本 PR1 已完成的改动为 serve 模块铺路：
- `ScenarioRunner` 改可长驻（Task 8）· 提供 `runOne` / `runStream` API
- `DefaultScenarioRegistry` 支持运行时 `register` / `unregister`（Task 8）
- `RunRecord` 类型化（Task 1）· serve 模块直接 JSON 序列化推 web

## 实现

独立 PR 立项，不属本次重构范围。
