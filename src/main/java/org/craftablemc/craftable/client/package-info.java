/**
 * 中文：客户端表现与输入边界。菜单/配方书事件和已排队的网络回复在客户端线程更新状态；
 * 这里的缓存、颜色、MAX 与路线证据均不是执行授权。服务端仍独立校验规则、来源、容量及预算。
 * 原版与专业设置共用注册定义，草稿只归对应页面拥有；会话所有者负责断线/重载失效。
 * English: Client presentation/input boundary. Menu/recipe-book events and enqueued network replies update state on the client thread;
 * caches, colors, MAX and route witnesses here are not execution authority. The server independently checks rules, sources, capacity and budgets.
 * Native/professional settings share registered definitions, drafts belong to their pages, and session owners handle disconnect/reload invalidation.
 */
package org.craftablemc.craftable.client;
