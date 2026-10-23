package com.example.mapper;

import com.example.entity.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    int insert(User user);

    int batchInsert(@Param("users") List<User> users);

    int updateUnknown(@Param("user") User user, @Param("flag") String flag);
}
