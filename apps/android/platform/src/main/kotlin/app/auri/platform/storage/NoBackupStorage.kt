package app.auri.platform.storage

import java.io.File

/**
 * 敏感文件（配对记录里的设备令牌、录音、耳机配置）放在 noBackupFilesDir：系统的云备份与换机迁移都不会带走那里的东西。
 * 清单里的备份规则（data_extraction_rules / backup_rules）是另一道独立的防线，两道互不依赖。
 */
object NoBackupStorage {
    /**
     * 敏感文件或目录 [name] 在 [noBackupDir] 里的位置。老版本把它放在 [legacyDir]（filesDir，会被备份）时，
     * 第一次用之前原样改名挪过来；新位置已经有了就不动（两份不能互相顶替）。幂等。
     */
    fun place(noBackupDir: File, legacyDir: File, name: String): File {
        val target = File(noBackupDir, name)
        val legacy = File(legacyDir, name)
        if (!target.exists() && legacy.exists()) {
            noBackupDir.mkdirs()
            // shortcut: 同一文件系统上的改名是原子的；改名失败（几乎不会发生）就当没有旧数据，不复制——免得敏感数据在两处各留一份；要补救时再换成“复制、校验、删除”
            legacy.renameTo(target)
        }
        return target
    }
}
