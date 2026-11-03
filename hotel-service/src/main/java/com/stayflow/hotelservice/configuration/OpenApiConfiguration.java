package com.stayflow.hotelservice.configuration;
import org.springframework.context.annotation.*;
import org.springdoc.core.models.GroupedOpenApi;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.media.*;
@Configuration
public class OpenApiConfiguration {
	@Bean
	OpenAPI api() {
		return new OpenAPI().info(new Info().title("StayFlow hotel-service").version("1.0")).components(new Components()
				.addSchemas("ErrorDTO",
						io.swagger.v3.core.converter.ModelConverters.getInstance()
								.read(com.stayflow.common.ErrorDTO.class).get("ErrorDTO"))
				.addSecuritySchemes("bearerAuth",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
				.addSecuritySchemes("serviceAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
						.bearerFormat("Service JWT")));
	}
	@Bean
	GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder().group("public")
				.pathsToExclude("/api/rooms/*/confirm-availability", "/api/rooms/*/release")
				.addOpenApiCustomizer(api -> customize(api, false)).build();
	}
	private void customize(OpenAPI api, boolean internal) {
		api.getComponents().addSchemas("ErrorDTO", io.swagger.v3.core.converter.ModelConverters.getInstance()
				.read(com.stayflow.common.ErrorDTO.class).get("ErrorDTO"));
		api.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
			if (path.equals("/booking"))
				op.getResponses().remove("200");
			if (internal || !path.equals("/user/register") && !path.equals("/user/auth"))
				op.addSecurityItem(new SecurityRequirement().addList(internal ? "serviceAuth" : "bearerAuth"));
			for (String status : new String[]{"400", "401", "403", "404", "409", "503"})
				op.getResponses().addApiResponse(status,
						new ApiResponse().description("Request failed")
								.content(new Content().addMediaType("application/json",
										new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorDTO")))));
		}));
	}
	@Bean
	GroupedOpenApi internalApi() {
		return GroupedOpenApi.builder().group("internal")
				.pathsToMatch("/api/rooms/*/confirm-availability", "/api/rooms/*/release")
				.addOpenApiCustomizer(api -> customize(api, true)).build();
	}
}
