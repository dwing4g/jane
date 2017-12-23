= Jane Framework =

simple, fast, powerful application server framework based on tcp and embedded database

Feature:
 * application server framework in Java8
 * TCP server and client framework based on custom optimized Apache Mina
 * simple, extensible and compact protocol on TCP
 * embedded database based on LevelDB with extremely fast in-memory cache
 * single process with multi-thread concurrent network and database
 * optimized for high performance (1 million TPS on one core of 3GHz PC)
 * simple implement but powerful (only ~17K lines of core source code)
 * easy to understand and use with code generation for Java bean code

Todo:
 * more tests
 * more optimization for speed, memory and I/O throughput

License:
 * GNU Lesser GPL

平台:
 * Oracle/Open JDK 8+ (需要ant作为构建环境, 推荐最新版本)
 * Linux 2.6+(64-bit)/Windows 7+(64-bit)/Mac OS X 10.9+ (开发和运行环境, 推荐4核4GB内存以上)
 * Eclipse 4.4+ (主要的Java开发环境, 推荐最新版本)
 * Visual Studio 2015+ (C#的开发环境, 推荐最新版本)

语言:
 * Java 8+ (框架和逻辑编写的主语言)
 * Lua 5.1 (Lua版本的bean和网络部分实现, 也用于代码生成等工具脚本)
 * C# 4.0+ (C#版本的bean和网络部分实现)

原则:
 * 以bean为核心
 * 概念和实现简单清晰

依赖库:
 * 日志库: slf4j-1.7.x logback-1.2.x
 * 网络库: mina-core-2.0.x (已内置到jane中)
 * 数据库: leveldb-jni

托管站点:
 * https://github.com/dwing4g/jane
 * https://git.oschina.net/dwing/jane
 * https://code.google.com/p/jane-framework/ (已不再维护)

参考/备用的开源库:
 * 基础库:
  * com.googlecode.concurrentlinkedhashmap: https://github.com/ben-manes/concurrentlinkedhashmap
  * kryo:           https://github.com/EsotericSoftware/kryo
  * snappy:         https://github.com/google/snappy
  * lz4:            https://github.com/lz4/lz4
  * gnu.trove.map:  http://trove.starlight-systems.com/
  * org.cliffc.high_scale_lib: http://sourceforge.net/projects/high-scale-lib/
 * 日志库:
  * slf4j:          http://www.slf4j.org/
  * logback:        http://logback.qos.ch/
  * log4j:          http://logging.apache.org/log4j/2.x/
 * 网络库:
  * mina:           http://mina.apache.org/
  * netty:          http://netty.io/
  * libuv:          https://github.com/libuv/libuv
  * libuv-jni:      https://github.com/dwing4g/libuv
  * jetty:          http://www.eclipse.org/jetty/download.html
 * 数据库:
  * leveldb:        https://github.com/google/leveldb
  * leveldb-jni:    https://github.com/dwing4g/leveldb
  * leveldb-jni:    https://code.google.com/r/bgrainger-leveldb/
  * leveldb-jni:    https://github.com/fusesource/leveldbjni
  * leveldb-java:   https://github.com/dain/leveldb
  * WiredTiger:     https://github.com/wiredtiger/wiredtiger
  * TerarkDB:       https://github.com/Terark/terark-db
  * edb:            http://limax-project.org
  * mapdb:          http://www.mapdb.org/
  * mvstore:        http://www.h2database.com/html/mvstore.html
  * berkeleydb/je:  http://www.oracle.com/technetwork/products/berkeleydb/downloads/index.html
  * perst:          http://www.mcobject.com/perst/
 * 脚本库:
  * luaj:           http://luaj.org/luaj/README.html
  * luaj:           https://github.com/dwing4g/luaj

相关软件:
 * JDK:             http://www.oracle.com/technetwork/java/javase/downloads/index.html
 * Apache Ant:      http://ant.apache.org/bindownload.cgi
 * Eclipse:         http://www.eclipse.org/downloads/
 * LuaJIT:          http://luajit.org/download.html

特性:
 * 高性能的网络/数据库IO, 单进程+多线程, 嵌入式数据库
 * 通用的框架和便利的逻辑实现, 以及异常安全保护
 * 统一使用bean作为网络传输和数据库value的单位, 自动通过配置生成bean代码
 * bean的定义包括8种数值类型, 字符串、二进制数据, 以及9种容器类型, 还支持数值和字符串常量
 * 支持发送简单的bean, 动态bean, 已序列化的bean, 以及RPC(ask/answer)统一处理/单独回调/超时处理
 * 支持简易高效的HTTP协议响应及回复
 * 内含简单实用的压缩和加密算法
 * 透明的基于文件和内存的面向对象数据库的操作、缓存、事务型持久化、热备份和增量备份, 基于记录加锁，支持死锁超时打断

TODO:
 * 更多的测试
 * 更多的性能、内存、IO优化
