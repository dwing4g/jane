## Jane Framework

simple, fast, powerful application server framework based on tcp and embedded database

### Feature
 * application server framework in Java11 (64-bit)
 * TCP server and client framework based on custom optimized Apache Mina
 * simple, extensible and compact protocol on TCP
 * embedded database based on LevelDB with extremely fast in-memory cache
 * single process with multi-thread concurrent network and database
 * optimized for ultra-high performance (million level TPS on common PC)
 * simple implement but powerful (only 18K+ lines of core source code)
 * easy to understand and use with code generation for Java bean code

### License
 * GNU Lesser GPL

### 平台
 * Oracle/Open JDK 11+(64-bit) (需要ant作为构建环境, 推荐最新版本)
 * Linux 2.6+(64-bit)/Windows 7+(64-bit)/Mac OS X 10.9+ (开发和运行环境, 推荐4核4GB内存以上)
 * Eclipse 或 IntelliJ IDEA (Java开发环境, 推荐最新版本, IDEA可用社区版)
 * Visual Studio (C#开发环境, 推荐最新版本)

### 语言
 * Java 11+ (框架和逻辑编写的主语言)
 * Lua 5.1 (Lua版本的bean和网络部分实现, 也用于代码生成等工具脚本)
 * C# 3.5+ (C#版本的bean和网络部分实现)

### 原则
 * 以bean为核心
 * 概念和实现简单清晰

### 依赖库
 * JRE模块: java.base, jdk.unsupported, java.xml, java.naming, java.logging, java.security.sasl
 * 日志库: slf4j-1.7.x logback-1.2.x
 * 网络库: mina-core-2.0.x (已精简优化并内置到jane中)
 * 数据库: leveldb-jni

### 托管站点
 * https://github.com/dwing4g/jane
 * https://gitee.com/dwing/jane
 * https://code.google.com/p/jane-framework/ (已不再维护)

### 参考/备用的开源库
###### 基础库:
  * com.googlecode.concurrentlinkedhashmap: https://github.com/ben-manes/concurrentlinkedhashmap
  * kryo:           https://github.com/EsotericSoftware/kryo
  * snappy:         https://github.com/google/snappy
  * lz4:            https://github.com/lz4/lz4
  * gnu.trove.map:  https://bitbucket.org/trove4j/trove/
  * org.cliffc.high_scale_lib: https://sourceforge.net/projects/high-scale-lib/
###### 日志库:
  * slf4j:          http://www.slf4j.org/
  * logback:        https://logback.qos.ch/
  * log4j:          https://logging.apache.org/log4j/2.x/index.html
###### 网络库:
  * mina:           https://mina.apache.org/
  * netty:          https://netty.io/
  * libuv:          https://github.com/libuv/libuv
  * libuv-jni:      https://github.com/dwing4g/libuv
  * jetty:          https://www.eclipse.org/jetty/download.php
###### 数据库:
  * leveldb:        https://github.com/google/leveldb
  * leveldb-jni:    https://github.com/dwing4g/leveldb
  * leveldb-jni:    https://github.com/fusesource/leveldbjni
  * leveldb-java:   https://github.com/dain/leveldb
  * WiredTiger:     https://github.com/wiredtiger/wiredtiger
  * TerarkDB:       https://github.com/Terark/terark-db
  * edb:            http://limax-project.org
  * mapdb:          https://mapdb.org/
  * mvstore:        https://www.h2database.com/html/mvstore.html
  * berkeleydb/je:  https://www.oracle.com/database/technologies/related/berkeleydb-downloads.html
  * perst:          https://www.mcobject.com/perst/
###### 脚本库:
  * luaj:           http://luaj.org/luaj/README.html
  * luaj:           https://github.com/dwing4g/luaj

### 相关软件
 * OpenJDK:         https://adoptium.net/
 * Apache Ant:      https://ant.apache.org/bindownload.cgi
 * LuaJIT:          https://luajit.org/download.html
 * IntelliJ IDEA:   https://www.jetbrains.com/idea/download/
 * Eclipse:         https://www.eclipse.org/downloads/

### 特性
 * 内嵌精简优化的mina高性能网络库
 * 内嵌支持win,mac,linux平台native的LevelDB高性能数据库及优化的jni接口
 * 多线程并发事务, 异常时回滚修改
 * 透明的基于文件和内存的面向对象数据库的操作、缓存、事务型持久化、热备份和增量备份, 基于记录加锁，支持死锁超时打断
 * 在通用框架的基础上便利地实现业务逻辑
 * 统一使用bean作为网络传输和数据库value的单位, 通过配置生成bean代码
 * bean的定义包括8种数值类型, 字符串、二进制数据, 以及9种容器类型, 还支持数值和字符串常量
 * 支持发送简单的bean, 动态bean, 已序列化的bean, 以及RPC(ask/answer)的统一处理/单独回调/超时处理
 * 支持简易高效的HTTP服务器响应及回应
 * 提供简单实用的压缩和加密算法
 * 提供简单高效的基于自动线程池的HTTP客户端异步请求
 * jane-core.jar不到400KB, jane-native.jar不到600KB
