package com.zwiki.repository.jpa;

import com.zwiki.domain.enums.TaskStatusEnum;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class TaskStatusEnumConverter implements AttributeConverter<TaskStatusEnum, String> {

    @Override
    public String convertToDatabaseColumn(TaskStatusEnum attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCode();
    }

    @Override
    public TaskStatusEnum convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (TaskStatusEnum value : TaskStatusEnum.values()) {
            if (dbData.equals(value.getCode())) {
                return value;
            }
        }
        return null;
    }
}
