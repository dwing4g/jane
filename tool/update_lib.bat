@echo off
setlocal
pushd %~dp0

set MAPDB=..\..\mapdb
set H2DB=..\..\h2database

	copy /y %MAPDB%\src\main\java\org\mapdb\*.java							..\lib\mapdb\org\mapdb\

	copy /y %H2DB%\h2\src\main\org\h2\compress\CompressDeflate.java			..\lib\mvstore\org\h2\compress\
	copy /y %H2DB%\h2\src\main\org\h2\compress\CompressLZF.java				..\lib\mvstore\org\h2\compress\
	copy /y %H2DB%\h2\src\main\org\h2\compress\Compressor.java				..\lib\mvstore\org\h2\compress\
	copy /y %H2DB%\h2\src\main\org\h2\engine\Constants.java					..\lib\mvstore\org\h2\engine\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\Chunk.java					..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\ConcurrentLinkedList.java		..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\Cursor.java					..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\CursorPos.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\DataUtils.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\FileStore.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\FreeSpaceBitSet.java			..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\MVMap.java					..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\MVMapConcurrent.java			..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\MVStore.java					..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\MVStoreTool.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\OffHeapStore.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\Page.java						..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\StreamStore.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\WriteBuffer.java				..\lib\mvstore\org\h2\mvstore\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\cache\CacheLongKeyLIRS.java	..\lib\mvstore\org\h2\mvstore\cache\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\cache\FilePathCache.java		..\lib\mvstore\org\h2\mvstore\cache\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\type\DataType.java			..\lib\mvstore\org\h2\mvstore\type\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\type\ObjectDataType.java		..\lib\mvstore\org\h2\mvstore\type\
	copy /y %H2DB%\h2\src\main\org\h2\mvstore\type\StringDataType.java		..\lib\mvstore\org\h2\mvstore\type\
	copy /y %H2DB%\h2\src\main\org\h2\security\AES.java						..\lib\mvstore\org\h2\security\
	copy /y %H2DB%\h2\src\main\org\h2\security\BlockCipher.java				..\lib\mvstore\org\h2\security\
	copy /y %H2DB%\h2\src\main\org\h2\security\SHA256.java					..\lib\mvstore\org\h2\security\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FileBase.java				..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FileChannelInputStream.java	..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FileChannelOutputStream.java	..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FilePath.java				..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FilePathDisk.java			..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FilePathEncrypt.java			..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FilePathNio.java				..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FilePathWrapper.java			..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\store\fs\FileUtils.java				..\lib\mvstore\org\h2\store\fs\
	copy /y %H2DB%\h2\src\main\org\h2\util\MathUtils.java					..\lib\mvstore\org\h2\util\
	copy /y %H2DB%\h2\src\main\org\h2\util\New.java							..\lib\mvstore\org\h2\util\

rem	WriteBuffer.java:
rem	//PATCH: jane begin
rem	public void setBuffer(ByteBuffer buf) {
rem		buff = buf;
rem	}
rem	//PATCH: jane end
rem	FilePathDisk.java: many unused comment for compilation
rem	CompressDeflate.java: DBException to RuntimeException

pause
