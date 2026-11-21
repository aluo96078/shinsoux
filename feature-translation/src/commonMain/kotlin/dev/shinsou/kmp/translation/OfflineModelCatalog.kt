package dev.shinsou.kmp.translation

/** One downloadable file of an offline model, pinned by size and SHA-256. */
public data class OfflineModelFile(
    val name: String,
    /** Path under [OfflineModelCatalog.ATTACHMENT_BASE_URL]. */
    val location: String,
    val size: Long,
    val sha256: String,
)

/** One translation direction of the offline engine. */
public data class OfflineModel(
    val from: String,
    val to: String,
    val version: String,
    val model: OfflineModelFile,
    val sourceVocab: OfflineModelFile,
    val targetVocab: OfflineModelFile,
    val shortlist: OfflineModelFile,
) {
    /** Directory name of the installed model, and the engine identity in the translation memory. */
    val id: String get() = "$from-$to-$version"
    val engine: String get() = "bergamot:$id"
    val files: List<OfflineModelFile> get() = listOf(model, sourceVocab, targetVocab, shortlist).distinctBy { it.name }
    val downloadBytes: Long get() = files.sumOf { it.size }
}

/**
 * The offline models this build can use (ADR-0004 §3.2): Mozilla's Firefox translation models
 * (MPL-2.0), every one paired with English. Each file is pinned here by SHA-256, so a download
 * is checked against this build rather than against whatever the network answers; a newer model
 * arrives with an app update.
 */
public object OfflineModelCatalog {
    public const val ATTACHMENT_BASE_URL: String = "https://firefox-settings-attachments.cdn.mozilla.net/"
    public const val PIVOT: String = "en"

    public val models: List<OfflineModel> = listOf(
        OfflineModel(
            from = "ja", to = "en", version = "2.1",
            model = OfflineModelFile("model.jaen.intgemm.alphas.bin", "main-workspace/translations-models/2b066368-11b5-4cee-92e8-e6156b334f80.bin", 43977787L, "3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302"),
            sourceVocab = OfflineModelFile("vocab.jaen.spm", "main-workspace/translations-models/6f4898e3-ebaa-4a79-a1af-93a4f65b96fa.spm", 1443222L, "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919"),
            targetVocab = OfflineModelFile("vocab.jaen.spm", "main-workspace/translations-models/6f4898e3-ebaa-4a79-a1af-93a4f65b96fa.spm", 1443222L, "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919"),
            shortlist = OfflineModelFile("lex.50.50.jaen.s2t.bin", "main-workspace/translations-models/4e6b3270-8a2f-491d-b717-f99749328622.bin", 9348172L, "525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4"),
        ),
        OfflineModel(
            from = "en", to = "ja", version = "2.3",
            model = OfflineModelFile("model.enja.intgemm.alphas.bin", "main-workspace/translations-models/4e19636e-4644-4072-8a05-9ef695d4a3ae.bin", 43849787L, "59ae659f9bb63e4f81f474fe3c03d3f4499434b5f9e779fab7c12a45f31fd562"),
            sourceVocab = OfflineModelFile("srcvocab.enja.spm", "main-workspace/translations-models/cdea5422-fe0d-4941-a653-ca090423e8f8.spm", 796275L, "970c98d174fc01e0339fbabbf45af36a4be3f26f819ec1a5ea1189f71e091889"),
            targetVocab = OfflineModelFile("trgvocab.enja.spm", "main-workspace/translations-models/5c1a663f-5736-4100-b681-c6737fe79cb5.spm", 827144L, "3b3d9f8f3a034d98d0a476f1794fa79c01e4e98a967ceb6777a66ba2d03ec1e1"),
            shortlist = OfflineModelFile("lex.50.50.enja.s2t.bin", "main-workspace/translations-models/87258444-8fac-4d3c-a175-9275e9d10ffa.bin", 4128360L, "edfb7eb47b98a2689b804ab3614c31b24f1257aa54b1154da3d85e1ae8152d9f"),
        ),
        OfflineModel(
            from = "ko", to = "en", version = "2.1",
            model = OfflineModelFile("model.koen.intgemm.alphas.bin", "main-workspace/translations-models/bf6d1f85-351f-4934-934d-8a7b0d118270.bin", 43977787L, "7cb30cbc0a86e242084756445268a2346136771c92b884d9b4ebb077f67d5507"),
            sourceVocab = OfflineModelFile("vocab.koen.spm", "main-workspace/translations-models/409950da-3a7f-4a0a-bd3e-9f655d7150eb.spm", 1410063L, "1c72b740ab793cdc3a8f16913dd6b4e806c77421077dd2d85edeb7be38418598"),
            targetVocab = OfflineModelFile("vocab.koen.spm", "main-workspace/translations-models/409950da-3a7f-4a0a-bd3e-9f655d7150eb.spm", 1410063L, "1c72b740ab793cdc3a8f16913dd6b4e806c77421077dd2d85edeb7be38418598"),
            shortlist = OfflineModelFile("lex.50.50.koen.s2t.bin", "main-workspace/translations-models/66a51334-41dd-4f08-bd22-6016035d6904.bin", 8617516L, "b26291f063ce216a74239275f0485534a38111203e59d42a2a56ba62d6eeb196"),
        ),
        OfflineModel(
            from = "en", to = "ko", version = "2.1",
            model = OfflineModelFile("model.enko.intgemm.alphas.bin", "main-workspace/translations-models/29e88605-8576-4a8e-be94-a5ebb4a738f7.bin", 43849787L, "ffe5ef2ce3f2e944055e35310bab186f6a70d533a0ee4394adf716a0e383afbb"),
            sourceVocab = OfflineModelFile("srcvocab.enko.spm", "main-workspace/translations-models/3d9dfbda-cd4f-445e-a69b-19e21e071385.spm", 791280L, "8d4e3141868efd410eb86c2b60d41aa6bc6b46e4c4b2c0b85e3792493c69d802"),
            targetVocab = OfflineModelFile("trgvocab.enko.spm", "main-workspace/translations-models/bef3bc40-bc91-4739-981d-c82a9d22fd31.spm", 815353L, "2ece21b28dd7022d36127d17423a74dabf544787dfc1cf614f7b565f93a4a1d3"),
            shortlist = OfflineModelFile("lex.50.50.enko.s2t.bin", "main-workspace/translations-models/85738400-bea4-4284-ba4c-e47e465f5331.bin", 6449612L, "96efba97d4bb93ab362e5569bea0f115184d2f68d63b67305ee663c18e32fbeb"),
        ),
        OfflineModel(
            from = "zh-Hans", to = "en", version = "2.1",
            model = OfflineModelFile("model.zhen.intgemm.alphas.bin", "main-workspace/translations-models/715efbde-c974-4696-981a-83c0c5f82ad5.bin", 43977787L, "5cd149601802fc8a18124a1c1306144dbbedc058630c4ddb2d53aa76fa9c7c06"),
            sourceVocab = OfflineModelFile("vocab.zhen.spm", "main-workspace/translations-models/17ff5d42-6d4c-42f8-ad3c-b8042b5bafa7.spm", 1359697L, "dff594318ab7d8b7b60b844ab98ebe6b932ae8045fab15235404c787715965b3"),
            targetVocab = OfflineModelFile("vocab.zhen.spm", "main-workspace/translations-models/17ff5d42-6d4c-42f8-ad3c-b8042b5bafa7.spm", 1359697L, "dff594318ab7d8b7b60b844ab98ebe6b932ae8045fab15235404c787715965b3"),
            shortlist = OfflineModelFile("lex.50.50.zhen.s2t.bin", "main-workspace/translations-models/ae5f53d2-59ec-4aad-944d-999e50d6a65c.bin", 9219192L, "8524dd4c93ffd7f0ae7be32f77a7c14fea7cdbbebe34ea6ad0ec368d5ddd8b13"),
        ),
        OfflineModel(
            from = "en", to = "zh-Hans", version = "2.2",
            model = OfflineModelFile("model.enzh.intgemm.alphas.bin", "main-workspace/translations-models/a7ff7d5e-e67e-406c-a34b-a7edea35b10e.bin", 43849787L, "4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c"),
            sourceVocab = OfflineModelFile("srcvocab.enzh.spm", "main-workspace/translations-models/ea98c52c-58dc-45d5-af23-38f2b029d020.spm", 806952L, "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5"),
            targetVocab = OfflineModelFile("trgvocab.enzh.spm", "main-workspace/translations-models/bddbda68-d4d2-4317-a0a1-119caa47525e.spm", 772004L, "aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d"),
            shortlist = OfflineModelFile("lex.50.50.enzh.s2t.bin", "main-workspace/translations-models/da8fccc0-31df-4665-9703-96d36606e019.bin", 6506248L, "4a5e5827788060f1d718a8132b69440929387514a045796e9b77f935db68c055"),
        ),
        OfflineModel(
            from = "zh-Hant", to = "en", version = "2.0",
            model = OfflineModelFile("model.zhen.intgemm.alphas.bin", "main-workspace/translations-models/20260522194230--6ca6db97-6bce-4110-981f-350e15965675--8323efd2-43e9-4886-9f60-35870d1744ea.bin", 43849787L, "0aee91790894458f5d367551f6edcd4c9cb97852c34f221bcbf9f4701ebcf0cd"),
            sourceVocab = OfflineModelFile("srcvocab.zhen.spm", "main-workspace/translations-models/20260522194213--ad6a9ed2-06ed-4c72-b6ba-aa4492bd582f--72fab356-41da-4bd6-a22c-aa5456ba3ea1.spm", 769669L, "5cc6a76611dbf86219f109141533606b15ecb34eee83673bb86b2c16b14734db"),
            targetVocab = OfflineModelFile("trgvocab.zhen.spm", "main-workspace/translations-models/20260522194217--5e1d7df1-80a8-4142-ae9d-0ff51b8c2fbc--5e39a274-9805-4d87-a950-39b3d95aac93.spm", 812572L, "7bf002db37c10d3b114cc5588d7fdcb16c57d0fd1e2c34354c22cc9f0b6c3c29"),
            shortlist = OfflineModelFile("lex.50.50.zhen.s2t.bin", "main-workspace/translations-models/20260522194215--6c386193-432a-422f-be5f-3cad0977e52b--a52e4a54-5f80-43ce-b873-1bc792ec1483.bin", 6385944L, "aa7daf6cfc85c0cd2c10e2944d66f6da55497c9c6408789f3adfded4074c2fb1"),
        ),
        OfflineModel(
            from = "en", to = "zh-Hant", version = "2.0",
            model = OfflineModelFile("model.enzh.intgemm.alphas.bin", "main-workspace/translations-models/20260522194155--76d2a34e-7ef2-4829-ac1a-a1319b852dc5--72be7771-db83-4218-bec5-44f021cbf00b.bin", 43849787L, "559ab90d723a58c1f1e2ab7cc12137bc667af5ba3e325e3eb30b5cdc930db520"),
            sourceVocab = OfflineModelFile("srcvocab.enzh.spm", "main-workspace/translations-models/20260522194144--b93467f8-2c0b-43f4-a662-19cae5c7f899--ae44a945-6ed0-44d2-88ac-d4b9c25f6c3a.spm", 803694L, "2266df70492162a249ab1c0154f929bd6098b246544c666c1a0d5a24dde7d2ea"),
            targetVocab = OfflineModelFile("trgvocab.enzh.spm", "main-workspace/translations-models/20260522194157--52f509a0-ac8b-48bf-ba9f-0c61a064a00a--85418cc0-208e-440b-ac6f-c56d242ca430.spm", 751671L, "22b91a4436d70b91ab8777c677252ab5fae2bc284d71f977df5206c110e3444c"),
            shortlist = OfflineModelFile("lex.50.50.enzh.s2t.bin", "main-workspace/translations-models/20260522194159--13a8d548-8d6a-46f6-ab8d-ea19098b7fe8--6cf4bdcb-dbe5-4e2d-af40-a02b440fdcc9.bin", 4057188L, "d891404d1436a7334df12539fe30a26f9e9f2b80bd42fdb8b5f8849e8a1e942b"),
        ),
    )

    /** The engine's language for an app or content language tag, or null when it has no model. */
    public fun language(tag: String): String? {
        val parts = tag.trim().replace('_', '-').split('-')
        val base = parts.first().lowercase()
        if (base != "zh") return base.takeIf { candidate -> candidate == PIVOT || models.any { it.from == candidate || it.to == candidate } }
        val rest = parts.drop(1).map(String::lowercase)
        val traditional = "hant" in rest || rest.any { it in setOf("tw", "hk", "mo") }
        return if (traditional) "zh-Hant" else "zh-Hans"
    }

    public fun model(from: String, to: String): OfflineModel? = models.firstOrNull { it.from == from && it.to == to }

    /** The models needed to translate [from] into [to]: one, or two through English; null when impossible. */
    public fun route(from: String, to: String): List<OfflineModel>? {
        val source = language(from) ?: return null
        val target = language(to) ?: return null
        if (source == target) return null
        if (source == PIVOT || target == PIVOT) return model(source, target)?.let(::listOf)
        val first = model(source, PIVOT) ?: return null
        val second = model(PIVOT, target) ?: return null
        return listOf(first, second)
    }
}
