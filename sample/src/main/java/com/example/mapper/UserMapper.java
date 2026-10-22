package com.example.mapper;

import com.example.entity.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    User selectById(@Param("id") Long id);

    List<User> search(@Param("userName") String userName,
                      @Param("age") Integer age);

    List<User> selectByIds(@Param("ids") List<Long> ids);

    int insert(User user);

    int updateSelective(User user);

    List<User> searchByPattern(@Param("userName") String userName);

    List<User> selectWithMap(@Param("params") java.util.Map<String, Object> params);
}
