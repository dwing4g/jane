= Jane Framework =

simple, fast, powerful application server framework based on tcp and embedded database

Feature:
 * application server framework in Java (JDK6)
 * TCP server and client framework based on Apache Mina
 * simple, extensible and compact protocol on TCP
 * embedded database based on LevelDB with extremely fast in-memory cache
 * single process with multi-thread concurrent network and database
 * optimized for high performance (1 million TPS on one core of 3GHz PC)
 * simple implement but powerful (only 12K+ lines of core source code)
 * easy to understand and use with code generation for Java bean code

Todo:
 * unit test
 * more optimization for speed, memory and I/O throughput
 * import database from text file

License:
 * GNU Lesser GPL

平台:
 * Oracle/Open JDK 6+ (需要ant作为构建环境)
 * Linux 2.6+/Windows 2000+ (运行环境, 推荐64位4核4GB内存以上, 开发可使用32位)
 * Eclipse 4.3+ (主要开发环境)
 * Visual Studio 2010+ (C#代码的开发环境)

语言:
 * Java 6+ (框架和逻辑编写的主语言)
 * Lua 5.1 (基于Luaj的实现, 目前主要用于代码生成的脚本, 也可作为逻辑调用的脚本)
 * C# 4.0+ (和Java版本兼容的bean部分实现和代码生成脚本)

原则:
 * 以bean为核心
 * 概念和实现简单清晰

依赖库:
 * 日志库: slf4j-1.7.x log4j-2.x
 * 网络库: mina-core-2.0.x
 * 数据库: leveldb-jni mapdb-1.0.x mvstore-1.4.x (推荐使用LevelDB)
 * 脚本库: luaj-jse-2.0.x
 * 基础库: com.googlecode.concurrentlinkedhashmap (高速并发hashmap/linkedhashmap)

托管站点:
 * https://github.com/dwing4g/jane
 * https://code.google.com/p/jane-framework/
 * https://git.oschina.net/dwing/jane

参考的开源库:
 * 日志库:
  * slf4j:          http://www.slf4j.org/
  * log4j:          http://logging.apache.org/log4j/2.x/
  * logback:        http://logback.qos.ch/
 * 网络库:
  * mina:           http://mina.apache.org/
  * netty:          http://netty.io/
  * rupy:           https://code.google.com/p/rupy/
  * tjws:           http://tjws.sourceforge.net/
 * 数据库:
  * leveldb:        https://code.google.com/p/leveldb/
  * leveldb-jni:    https://code.google.com/r/dwing4g-leveldbjni/
  * leveldb-jni:    https://code.google.com/r/bgrainger-leveldb/
  * leveldb-jni:    https://github.com/fusesource/leveldbjni
  * leveldb-java:   https://github.com/dain/leveldb
  * mapdb:          http://www.mapdb.org/
  * mvstore:        http://www.h2database.com/html/mvstore.html
  * berkeleydb/je:  http://www.oracle.com/technetwork/products/berkeleydb/downloads/index.html
  * perst:          http://www.mcobject.com/perst/
 * 脚本库:
  * luaj:           http://luaj.org/luaj/README.html
 * 基础库:
  * com.googlecode.concurrentlinkedhashmap: https://code.google.com/p/concurrentlinkedhashmap/
  * kryo:           https://github.com/EsotericSoftware/kryo
  * gnu.trove.map:  http://trove.starlight-systems.com/
  * org.cliffc.high_scale_lib: http://sourceforge.net/projects/high-scale-lib/

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
 * 支持发送简单的bean, 连续发送bean, 广播bean, rpc统一处理/单独回调/超时处理
 * 支持简易高效的HTTP协议响应及回复
 * 内含简单实用的压缩和加密算法
 * 透明的基于文件和内存的面向对象数据库的操作、缓存、事务型持久化和热备份, 基于记录加锁，支持死锁超时打断

TODO:
 * 网络: 并发/吞吐量调优
 * 数据库: 内存优化, 数据库的文本格式导入工具
 * 全面的测试
