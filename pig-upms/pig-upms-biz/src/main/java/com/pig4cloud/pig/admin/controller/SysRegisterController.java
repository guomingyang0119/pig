package com.pig4cloud.pig.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.pig4cloud.pig.admin.api.dto.RegisterUserDTO;
import com.pig4cloud.pig.admin.service.SysUserService;
import com.pig4cloud.pig.common.core.constant.enums.YesNoEnum;
import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.common.data.resolver.ParamResolver;
import com.pig4cloud.pig.common.log.annotation.SysLog;
import com.pig4cloud.pig.common.security.annotation.Inner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * @author lengleng
 * @date 2022/3/30
 * <p>
 * 客户端注册功能 register.user = false
 */
@Inner(value = false)
@RestController
@RequestMapping("/register")
@RequiredArgsConstructor
public class SysRegisterController {

	private final SysUserService userService;

	/**
	 * 注册用户
	 * @param userDto 用户信息
	 * @return success/false
	 */
	@SysLog("注册用户")
	@PostMapping("/user")
	@ConditionalOnProperty(name = "register.user", matchIfMissing = true)
	public R<Boolean> registerUser(@RequestBody RegisterUserDTO userDto) {
		if (!YesNoEnum.YES.getCode().equals(ParamResolver.getStr("SITE_REGISTER_ENABLE", YesNoEnum.NO.getCode()))) {
			return R.failed("系统已关闭注册功能");
		}
		if (userDto == null || StrUtil.isBlank(userDto.getUsername()) || StrUtil.isBlank(userDto.getPassword())) {
			return R.failed("用户名和密码不能为空");
		}
		// 公开注册只接收账号密码，不允许通过请求体占用未经验证的手机号。
		RegisterUserDTO registerDto = new RegisterUserDTO();
		registerDto.setUsername(userDto.getUsername());
		registerDto.setPassword(userDto.getPassword());
		return userService.registerUser(registerDto);
	}

	/**
	 * 重置用户密码
	 * @param userDto 用户信息
	 * @return success/false
	 */
	@SysLog("重置用户密码")
	@PostMapping("/password")
	public R<Boolean> resetUserPassword(@RequestBody RegisterUserDTO userDto) {
		return userService.resetUserPassword(userDto);
	}

	/**
	 * 找回密码
	 * @param userDto 用户信息
	 * @param code 验证码
	 * @return success/false
	 */
	@SysLog("找回用户密码")
	@PostMapping("/forget/{code}")
	public R<Boolean> forgetUserPassword(@RequestBody RegisterUserDTO userDto, @PathVariable String code) {
		return userService.forgetUserPassword(userDto, code);
	}

}
