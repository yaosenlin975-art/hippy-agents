package com.lin.hippyagent.core.knowledge

/**
 * 实体提取模式定义 - 用于从文本中识别各类实体的正则表达式和关键词。
 *
 * 支持的实体类型：
 * - TECHNOLOGY: 编程语言、框架、工具
 * - PERSON: 人名（中文、英文）
 * - PROJECT: 项目名称
 * - LOCATION: 地名
 * - CONCEPT: 技术概念
 */
object EntityPatterns {

    // ── 技术实体 ──────────────────────────────────────────

    /** 编程语言 */
    val PROGRAMMING_LANGUAGES = setOf(
        "Kotlin", "Java", "Python", "Go", "Rust", "TypeScript", "JavaScript",
        "C", "C++", "C#", "Swift", "Objective-C", "PHP", "Ruby", "Scala",
        "Groovy", "Dart", "Lua", "R", "MATLAB", "Perl", "Haskell", "Elixir",
        "Clojure", "F#", "Julia", "Zig", "Nim", "Crystal"
    )

    /** 框架和库 */
    val FRAMEWORKS = setOf(
        "Android", "Android SDK", "Jetpack Compose", "Jetpack", "Room",
        "Retrofit", "OkHttp", "Hilt", "Dagger", "Koin", "Coroutines", "Flow",
        "React", "React Native", "Vue", "Vue.js", "Angular", "Next.js", "Nuxt",
        "Spring", "Spring Boot", "Spring Framework",
        "Django", "Flask", "FastAPI", "Express", "NestJS", "Koa",
        "Flutter", "SwiftUI", "UIKit",
        "TensorFlow", "PyTorch", "Keras", "scikit-learn",
        "Node.js", "Deno", "Bun",
        "Gradle", "Maven", "CMake", "Meson",
        "JUnit", "Mockito", "Espresso", "Robolectric",
        "kotlinx.serialization", "Gson", "Jackson", "Moshi"
    )

    /** 工具和平台 */
    val TOOLS = setOf(
        "Docker", "Kubernetes", "K8s", "Git", "GitHub", "GitLab", "Bitbucket",
        "Jenkins", "GitHub Actions", "CircleCI", "Travis CI",
        "VS Code", "IntelliJ IDEA", "Android Studio", "Xcode",
        "Linux", "macOS", "Windows", "Ubuntu", "Debian", "CentOS",
        "AWS", "GCP", "Azure", "Firebase", "Supabase",
        "Nginx", "Apache", "Caddy",
        "Redis", "PostgreSQL", "MySQL", "MongoDB", "SQLite", "Room",
        "Kafka", "RabbitMQ", "gRPC", "GraphQL", "REST",
        "npm", "yarn", "pnpm", "pip", "cargo", "brew"
    )

    /** 技术实体匹配模式（大小写敏感的完整词匹配） */
    val TECHNOLOGY_KEYWORDS: Set<String> = PROGRAMMING_LANGUAGES + FRAMEWORKS + TOOLS

    /** 技术实体正则：匹配大写开头的技术名词 */
    val TECHNOLOGY_REGEX = Regex(
        "\\b(${TECHNOLOGY_KEYWORDS.joinToString("|") { Regex.escape(it) }})\\b"
    )

    // ── 人名实体 ──────────────────────────────────────────

    /** 英文人名模式：首字母大写的两个或三个单词 */
    val PERSON_PATTERNS = listOf(
        // "John Smith", "Tim Cook" 等
        Regex("\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+)+)\\b"),
        // 带中间名缩写: "John D. Smith"
        Regex("\\b([A-Z][a-z]+\\s[A-Z]\\.\\s[A-Z][a-z]+)\\b")
    )

    /** 中文人名模式：2-4 个连续中文字符（需结合上下文判断） */
    val CHINESE_PERSON_REGEX = Regex("[\\u4e00-\\u9fa5]{2,4}")

    /** 人名上下文关键词：出现在这些词附近时更可能是人名 */
    val PERSON_CONTEXT_KEYWORDS = listOf(
        "作者", "开发者", "创始人", "负责人", "开发者", "维护者",
        "author", "developer", "creator", "maintainer", "owner",
        "by", "from", "wrote", "created", "designed"
    )

    // ── 项目实体 ──────────────────────────────────────────

    /** 项目名称模式 */
    val PROJECT_PATTERNS = listOf(
        // 带项目后缀: "HippyAgent", "MyProject"
        Regex("\\b([A-Z][a-zA-Z0-9]+(?:Project|App|SDK|Lib|API|Tool|Kit))\\b"),
        // 引号中的项目名: "'my-project'"
        Regex("['\"]([a-zA-Z][a-zA-Z0-9_-]{2,})['\"]"),
        // 路径中的项目名: "/project-name/"
        Regex("/([a-zA-Z][a-zA-Z0-9_-]{2,})/")
    )

    /** 项目上下文关键词 */
    val PROJECT_CONTEXT_KEYWORDS = listOf(
        "项目", "仓库", "代码库", "模块", "应用",
        "project", "repository", "repo", "module", "application", "app"
    )

    // ── 地点实体 ──────────────────────────────────────────

    /** 城市名称 */
    val CITIES = setOf(
        "北京", "上海", "深圳", "杭州", "广州", "成都", "武汉", "南京",
        "西安", "重庆", "苏州", "天津", "长沙", "郑州", "东莞", "青岛",
        "Beijing", "Shanghai", "Shenzhen", "Hangzhou", "Guangzhou", "Chengdu",
        "Tokyo", "Seoul", "Singapore", "London", "Paris", "Berlin",
        "New York", "San Francisco", "Seattle", "Austin", "Boston",
        "Toronto", "Sydney", "Melbourne", "Mumbai", "Bangalore"
    )

    /** 国家名称 */
    val COUNTRIES = setOf(
        "中国", "美国", "日本", "韩国", "英国", "法国", "德国", "印度",
        "加拿大", "澳大利亚", "新加坡",
        "China", "USA", "Japan", "Korea", "UK", "France", "Germany",
        "India", "Canada", "Australia", "Singapore", "Taiwan", "Singapore"
    )

    /** 地点正则 */
    val LOCATION_REGEX = Regex(
        "\\b(${(CITIES + COUNTRIES).joinToString("|") { Regex.escape(it) }})\\b"
    )

    // ── 概念实体 ──────────────────────────────────────────

    /** 技术概念关键词 */
    val CONCEPT_KEYWORDS = setOf(
        "架构", "设计模式", "微服务", "单体应用", "前后端分离",
        "依赖注入", "控制反转", "观察者模式", "工厂模式", "单例模式",
        "CI/CD", "DevOps", "敏捷开发", "Scrum", "Kanban",
        "机器学习", "深度学习", "自然语言处理", "NLP", "计算机视觉",
        "RESTful", "API设计", "数据库设计", "缓存策略", "消息队列",
        "并发", "异步", "协程", "线程池", "负载均衡",
        "architecture", "design pattern", "microservice", "monolith",
        "dependency injection", "inversion of control",
        "machine learning", "deep learning", "neural network",
        "concurrency", "asynchronous", "thread pool", "load balancing",
        "caching", "database", "authentication", "authorization",
        "refactoring", "code review", "unit test", "integration test",
        "性能优化", "内存泄漏", "ANR", "崩溃", "异常处理"
    )

    /** 概念正则 */
    val CONCEPT_REGEX = Regex(
        "\\b(${CONCEPT_KEYWORDS.joinToString("|") { Regex.escape(it) }})\\b",
        RegexOption.IGNORE_CASE
    )

    // ── 关系模式 ──────────────────────────────────────────

    /** 关系提取模式 */
    val RELATION_PATTERNS = mapOf(
        // "X 使用 Y" / "X uses Y"
        RelationType.USED_IN to listOf(
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:使用|用|uses?|utilizes?)\\s+([\\w\\u4e00-\\u9fa5]+)"),
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:基于|based on)\\s+([\\w\\u4e00-\\u9fa5]+)")
        ),
        // "X 依赖 Y" / "X depends on Y"
        RelationType.DEPENDS_ON to listOf(
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:依赖|需要|depends? on|requires?|needs?)\\s+([\\w\\u4e00-\\u9fa5]+)")
        ),
        // "X 属于 Y" / "X belongs to Y"
        RelationType.BELONGS_TO to listOf(
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:属于|归属|belongs? to|part of)\\s+([\\w\\u4e00-\\u9fa5]+)")
        ),
        // "X 创建了 Y" / "X created Y"
        RelationType.CREATED_BY to listOf(
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:创建|开发|开发了|created|built|developed)\\s+(?:了)?([\\w\\u4e00-\\u9fa5]+)")
        ),
        // "X 与 Y 相关" / "X is related to Y"
        RelationType.RELATED_TO to listOf(
            Regex("([\\w\\u4e00-\\u9fa5]+)\\s*(?:与|和|关联|related to|associated with)\\s+([\\w\\u4e00-\\u9fa5]+)")
        )
    )
}

