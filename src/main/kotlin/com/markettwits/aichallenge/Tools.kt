package com.markettwits.aichallenge

import kotlinx.serialization.json.*

object Tools {
    fun getAllTools(): List<Tool> {
        return listOf(
            getAssessFitnessLevelTool(),
            getGenerateTrainingPlanTool(),
            getRecoveryRecommendationsTool()
        )
    }

    private fun getAssessFitnessLevelTool(): Tool {
        return Tool(
            name = "assess_fitness_level",
            description = "Оценивает уровень физической подготовки на основе данных пользователя и возвращает рекомендацию",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("age", buildJsonObject {
                        put("type", "integer")
                        put("description", "Возраст пользователя")
                    })
                    put("running_experience", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("beginner")
                            add("intermediate")
                            add("advanced")
                        })
                        put("description", "Опыт бега (новичок, средний уровень, продвинутый)")
                    })
                    put("weekly_runs", buildJsonObject {
                        put("type", "integer")
                        put("description", "Количество пробежек в неделю")
                    })
                    put("max_distance_km", buildJsonObject {
                        put("type", "number")
                        put("description", "Максимальная дистанция, которую может пробежать (км)")
                    })
                },
                required = listOf("age", "running_experience", "weekly_runs", "max_distance_km")
            )
        )
    }

    private fun getGenerateTrainingPlanTool(): Tool {
        return Tool(
            name = "generate_training_plan",
            description = "Создаёт персональный 4-недельный тренировочный план по бегу на основе уровня подготовки",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("fitness_level", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("beginner")
                            add("intermediate")
                            add("advanced")
                        })
                        put("description", "Уровень физической подготовки")
                    })
                    put("goal", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("endurance")
                            add("speed")
                            add("5k")
                            add("10k")
                            add("half_marathon")
                        })
                        put("description", "Цель тренировки")
                    })
                    put("days_per_week", buildJsonObject {
                        put("type", "integer")
                        put("description", "Количество дней тренировок в неделю (3-6)")
                    })
                },
                required = listOf("fitness_level", "goal", "days_per_week")
            )
        )
    }


    private fun getRecoveryRecommendationsTool(): Tool {
        return Tool(
            name = "get_recovery_recommendations",
            description = "Возвращает рекомендации по питанию, отдыху и восстановлению для бегуна",
            input_schema = InputSchema(
                type = "object",
                properties = buildJsonObject {
                    put("fitness_level", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("beginner")
                            add("intermediate")
                            add("advanced")
                        })
                        put("description", "Уровень физической подготовки")
                    })
                    put("training_intensity", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("light")
                            add("moderate")
                            add("high")
                        })
                        put("description", "Интенсивность тренировок")
                    })
                },
                required = listOf("fitness_level", "training_intensity")
            )
        )
    }

    fun executeTool(toolName: String, input: JsonObject): String {
        return try {
            when (toolName) {
                "assess_fitness_level" -> executeAssessFitnessLevel(input)
                "generate_training_plan" -> executeGenerateTrainingPlan(input)
                "get_recovery_recommendations" -> executeGetRecoveryRecommendations(input)
                else -> "Unknown tool: $toolName"
            }
        } catch (e: Exception) {
            "Error executing tool $toolName: ${e.message}"
        }
    }

    private fun executeAssessFitnessLevel(input: JsonObject): String {
        val age = input["age"]?.jsonPrimitive?.int ?: return "Missing age"
        val experience = input["running_experience"]?.jsonPrimitive?.content ?: return "Missing running_experience"
        val weeklyRuns = input["weekly_runs"]?.jsonPrimitive?.int ?: return "Missing weekly_runs"
        val maxDistance = input["max_distance_km"]?.jsonPrimitive?.double ?: return "Missing max_distance_km"

        val level = when {
            experience == "beginner" || maxDistance < 5 -> "beginner"
            experience == "advanced" && maxDistance > 15 && weeklyRuns >= 5 -> "advanced"
            else -> "intermediate"
        }

        val maxWeeklyKm = when (level) {
            "beginner" -> 15
            "intermediate" -> 40
            else -> 70
        }

        val recommendedDays = when (level) {
            "beginner" -> 3
            "intermediate" -> 4
            else -> 5
        }

        val advice = when (level) {
            "beginner" -> "Начинайте медленно, фокусируйтесь на построении базы. Не увеличивайте дистанцию более чем на 10% в неделю."
            "intermediate" -> "Добавьте интервальные тренировки для улучшения скорости. Обязательно включайте дни отдыха."
            else -> "Разнообразьте тренировки: темповые пробежки, интервалы, длинные медленные пробежки. Следите за восстановлением."
        }

        return buildJsonObject {
            put("level", level)
            put("max_weekly_km", maxWeeklyKm)
            put("recommended_days", recommendedDays)
            put("advice", advice)
        }.toString()
    }

    private fun executeGenerateTrainingPlan(input: JsonObject): String {
        val fitnessLevel = input["fitness_level"]?.jsonPrimitive?.content ?: return "Missing fitness_level"
        val goal = input["goal"]?.jsonPrimitive?.content ?: return "Missing goal"
        val daysPerWeek = input["days_per_week"]?.jsonPrimitive?.int ?: return "Missing days_per_week"

        val weekPlan = when (fitnessLevel) {
            "beginner" -> generateBeginnerPlan(goal, daysPerWeek)
            "intermediate" -> generateIntermediatePlan(goal, daysPerWeek)
            else -> generateAdvancedPlan(goal, daysPerWeek)
        }

        return buildJsonObject {
            put("week_1", weekPlan["week_1"] ?: JsonNull)
            put("week_2", weekPlan["week_2"] ?: JsonNull)
            put("week_3", weekPlan["week_3"] ?: JsonNull)
            put("week_4", weekPlan["week_4"] ?: JsonNull)
            put("tips", "Слушайте свое тело, отдыхайте при необходимости, пейте достаточно воды, делайте растяжку после каждой тренировки")
        }.toString()
    }

    private fun generateBeginnerPlan(goal: String, days: Int): Map<String, JsonObject> {
        return mapOf(
            "week_1" to buildJsonObject {
                put("Monday", "Легкая пробежка 2 км, темп комфортный, 15-20 мин")
                put("Tuesday", "Отдых или легкая ходьба 30 мин")
                put("Wednesday", "Интервалы: 5 мин разминка, 4x(1 мин бег, 2 мин ходьба), 5 мин заминка")
                put("Thursday", "Отдых")
                put("Friday", "Легкая пробежка 2.5 км, 20-25 мин")
                put("Saturday", "Кросс-тренинг (велосипед, плавание) 30 мин")
                put("Sunday", "Длинная пробежка 3 км, медленный темп")
            },
            "week_2" to buildJsonObject {
                put("Monday", "Легкая пробежка 2.5 км, 20-25 мин")
                put("Tuesday", "Отдых или йога")
                put("Wednesday", "Интервалы: 5 мин разминка, 5x(1 мин быстро, 2 мин медленно), заминка")
                put("Thursday", "Отдых")
                put("Friday", "Темповая пробежка 3 км, умеренный темп")
                put("Saturday", "Кросс-тренинг 35 мин")
                put("Sunday", "Длинная пробежка 4 км, медленный темп")
            },
            "week_3" to buildJsonObject {
                put("Monday", "Легкая пробежка 3 км")
                put("Tuesday", "Отдых")
                put("Wednesday", "Интервалы: 6x(1 мин быстро, 1.5 мин медленно)")
                put("Thursday", "Отдых или легкая ходьба")
                put("Friday", "Темповая пробежка 3.5 км")
                put("Saturday", "Кросс-тренинг 40 мин")
                put("Sunday", "Длинная пробежка 4.5 км")
            },
            "week_4" to buildJsonObject {
                put("Monday", "Легкая пробежка 3 км")
                put("Tuesday", "Отдых")
                put("Wednesday", "Интервалы: 6x(1.5 мин быстро, 1.5 мин медленно)")
                put("Thursday", "Отдых")
                put("Friday", "Темповая пробежка 4 км")
                put("Saturday", "Отдых или легкая активность")
                put("Sunday", "Целевая пробежка 5 км в комфортном темпе")
            }
        )
    }

    private fun generateIntermediatePlan(goal: String, days: Int): Map<String, JsonObject> {
        return mapOf(
            "week_1" to buildJsonObject {
                put("Monday", "Легкая пробежка 5 км, восстановительный темп")
                put("Tuesday", "Интервалы на треке: 8x400м (отдых 90 сек)")
                put("Wednesday", "Кросс-тренинг или отдых")
                put("Thursday", "Темповая пробежка 6 км, умеренно-тяжелый темп")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 5 км")
                put("Sunday", "Длинная пробежка 10 км, медленный темп")
            },
            "week_2" to buildJsonObject {
                put("Monday", "Легкая пробежка 6 км")
                put("Tuesday", "Холмы: 6x(2 мин в горку, 2 мин вниз)")
                put("Wednesday", "Отдых или йога")
                put("Thursday", "Темповая пробежка 7 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 5 км")
                put("Sunday", "Длинная пробежка 12 км")
            },
            "week_3" to buildJsonObject {
                put("Monday", "Легкая пробежка 6 км")
                put("Tuesday", "Интервалы: 6x800м (отдых 2 мин)")
                put("Wednesday", "Отдых")
                put("Thursday", "Темповая пробежка 8 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 6 км")
                put("Sunday", "Длинная пробежка 14 км")
            },
            "week_4" to buildJsonObject {
                put("Monday", "Легкая пробежка 5 км")
                put("Tuesday", "Интервалы: 4x1000м (отдых 2.5 мин)")
                put("Wednesday", "Отдых")
                put("Thursday", "Темповая пробежка 6 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 4 км")
                put("Sunday", "Целевая пробежка 10 км в целевом темпе")
            }
        )
    }

    private fun generateAdvancedPlan(goal: String, days: Int): Map<String, JsonObject> {
        return mapOf(
            "week_1" to buildJsonObject {
                put("Monday", "Легкая пробежка 8 км + силовая тренировка")
                put("Tuesday", "Интервалы: 10x400м (отдых 60 сек), целевой темп 5к")
                put("Wednesday", "Восстановительная пробежка 6 км")
                put("Thursday", "Темповая пробежка 10 км, темп на 15-20 сек медленнее целевого")
                put("Friday", "Отдых или легкий кросс-тренинг")
                put("Saturday", "Легкая пробежка 8 км")
                put("Sunday", "Длинная пробежка 16 км, прогрессивный темп")
            },
            "week_2" to buildJsonObject {
                put("Monday", "Легкая пробежка 8 км + силовая")
                put("Tuesday", "Лестничные интервалы: 800м-1200м-1600м-1200м-800м (отдых 90 сек)")
                put("Wednesday", "Восстановительная пробежка 8 км")
                put("Thursday", "Темповая пробежка 12 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 8 км")
                put("Sunday", "Длинная пробежка 18 км с последними 5 км в марафонском темпе")
            },
            "week_3" to buildJsonObject {
                put("Monday", "Легкая пробежка 10 км + силовая")
                put("Tuesday", "Интервалы: 8x800м (отдых 90 сек)")
                put("Wednesday", "Восстановительная пробежка 8 км")
                put("Thursday", "Темповая пробежка 14 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 8 км")
                put("Sunday", "Длинная пробежка 20 км, негативный сплит")
            },
            "week_4" to buildJsonObject {
                put("Monday", "Легкая пробежка 8 км")
                put("Tuesday", "Короткие интервалы: 6x1000м (отдых 2 мин)")
                put("Wednesday", "Восстановительная пробежка 6 км")
                put("Thursday", "Темповая пробежка 8 км")
                put("Friday", "Отдых")
                put("Saturday", "Легкая пробежка 5 км")
                put("Sunday", "Гонка или целевая пробежка полумарафон")
            }
        )
    }

    private fun executeGetRecoveryRecommendations(input: JsonObject): String {
        val fitnessLevel = input["fitness_level"]?.jsonPrimitive?.content ?: return "Missing fitness_level"
        val intensity = input["training_intensity"]?.jsonPrimitive?.content ?: return "Missing training_intensity"

        val nutrition = when (intensity) {
            "light" -> "Сбалансированное питание с акцентом на цельные продукты. Углеводы: 3-5 г/кг веса, белки: 1.2-1.4 г/кг."
            "moderate" -> "Увеличьте углеводы до 5-7 г/кг для энергии. Белки: 1.4-1.6 г/кг для восстановления. Не забывайте о полезных жирах."
            else -> "Высокоуглеводная диета: 7-10 г/кг. Белки: 1.6-2 г/кг. Подумайте о спортивном питании для быстрого восстановления."
        }

        val sleep = when (fitnessLevel) {
            "beginner" -> "7-8 часов качественного сна. Соблюдайте режим, ложитесь и вставайте в одно время."
            "intermediate" -> "8-9 часов сна. Рассмотрите дневной сон 20-30 мин после тяжелых тренировок."
            else -> "8-10 часов сна обязательно. Следите за качеством сна, используйте трекеры. Дневной сон 30-60 мин рекомендуется."
        }

        val stretching = buildString {
            append("Динамическая растяжка перед пробежкой: махи ногами, выпады с поворотом, подъемы коленей. ")
            append("Статическая растяжка после: квадрицепсы, подколенные сухожилия, икроножные, ягодичные - по 30 сек на группу.")
            if (fitnessLevel == "advanced") {
                append(" Добавьте йогу или пилатес 1-2 раза в неделю.")
            }
        }

        val injuryPrevention = buildString {
            append("Правильная обувь - меняйте каждые 600-800 км. ")
            append("Постепенное увеличение нагрузки - не более 10% в неделю. ")
            append("Силовые тренировки 2 раза в неделю на мышцы кора и ног. ")
            if (intensity != "light") {
                append("Массаж или роллинг пеной 3-4 раза в неделю. ")
            }
            append("Слушайте свое тело - боль это сигнал, не игнорируйте её.")
        }

        return buildJsonObject {
            put("nutrition", nutrition)
            put("sleep", sleep)
            put("stretching", stretching)
            put("injury_prevention", injuryPrevention)
        }.toString()
    }

}
