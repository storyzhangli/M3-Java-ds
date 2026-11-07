package com.zl.d25.equipment;

import com.zl.d25.common.Result;
import com.zl.d25.dto.ChatRequest;
import com.zl.d25.equipment.dto.EquipmentChatResponse;
import com.zl.d25.history.ChatHistoryService;
import com.zl.d25.history.config.HistoryProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备客服/技术工程师对话 API。会话查询清空继续复用既有历史接口。
 */
@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentChatController {

    private final EquipmentChatService equipmentChatService;

    private final ChatHistoryService historyService;

    private final HistoryProperties historyProperties;

    public EquipmentChatController(EquipmentChatService equipmentChatService,
                                   ChatHistoryService historyService,
                                   HistoryProperties historyProperties) {
        this.equipmentChatService = equipmentChatService;
        this.historyService = historyService;
        this.historyProperties = historyProperties;
    }

    @PostMapping("/chat")
    public Result<EquipmentChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                              HttpServletRequest httpRequest) {
        String owner = historyService.resolveOwner(
                httpRequest.getHeader(historyProperties.getDevUserHeader()));
        return Result.success(equipmentChatService.chat(request, owner));
    }
}
