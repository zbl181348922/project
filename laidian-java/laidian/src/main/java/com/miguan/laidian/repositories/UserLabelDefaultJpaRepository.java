package com.miguan.laidian.repositories;

import com.miguan.laidian.entity.UserLabelDefault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


public interface UserLabelDefaultJpaRepository extends JpaRepository<UserLabelDefault, Long> {

    UserLabelDefault findTopByChannelId(String channelId);

    UserLabelDefault findTopByChannelIdAndState(String channelId, int state);

    @Query(value = "select * from user_label_default where concat(channel_id,',') like ?1 and state = 1 order by updated_at desc LIMIT 1",nativeQuery = true)
    UserLabelDefault getLabelDefaultByChannelId(String channelId);
}
