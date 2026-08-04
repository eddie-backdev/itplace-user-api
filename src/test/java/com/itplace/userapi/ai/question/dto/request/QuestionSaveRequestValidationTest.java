package com.itplace.userapi.ai.question.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuestionSaveRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void rejectsBlankQuestionAndCategory() {
        QuestionSaveRequest request = new QuestionSaveRequest();
        request.setQuestion(" ");
        request.setCategory("");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("question", "category");
    }

    @Test
    void rejectsOversizedQuestionAndCategory() {
        QuestionSaveRequest request = new QuestionSaveRequest();
        request.setQuestion("q".repeat(201));
        request.setCategory("c".repeat(101));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("question", "category");
    }
}
