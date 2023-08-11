package com.example.silverrock.matching.Service;//package com.example.silverrock.matching.Service;

import com.example.silverrock.global.Response.BaseException;
import com.example.silverrock.login.jwt.JwtService;
import com.example.silverrock.matching.Entity.Matching;
import com.example.silverrock.matching.dto.PostMatcingReq;
import com.example.silverrock.matching.repository.MatchingRequestRepository;
import com.example.silverrock.user.User;
import com.example.silverrock.user.UserRepository;
import com.example.silverrock.user.dto.GetS3Res;
import com.example.silverrock.user.dto.GetUserRes;
import com.example.silverrock.user.profile.Profile;
import com.example.silverrock.user.profile.ProfileRepository;
import com.example.silverrock.user.profile.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.example.silverrock.global.Response.BaseResponseStatus.*;

@RequiredArgsConstructor
@Service
public class MatchingService {
    private final MatchingRequestRepository matchingRequestRepository;
    private final JwtService jwtService;
    private final ProfileService profileService;
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;

//    //매칭 요청 MatchingRequest
//    public MatchingRequest(){
//        //매칭고유번호 생성, 매칭여부=false
//
//
//    }
//
//    //매칭 수락
//    public void match(PostMatcingReq postMatcingReq){
//        Matching matching =new Matching(
//                postMatcingReq.getSender(), postMatcingReq.getReceiver(), false
//        );
//        matchingR.save(matching);
//    }
//
//    //매칭 거절



    //내가 받은 매칭 요청 조회
    public List<GetUserRes> getReceivedMatchings(Long userId) throws BaseException {
        User me = userRepository.findUserById(userId).orElse(null);   //id로 user객체 가져와
        List<Matching> receivedmatchings = matchingRequestRepository.findMatchingByReceiver(me).get();  // receiver가 '나'인 매칭 조회
        User sender;
        List<User> senders = new ArrayList<>();

        if(receivedmatchings.isEmpty()){
            throw new BaseException(NONE_RECEIVED);
        }

        for(Matching matching : receivedmatchings){
            sender = matching.getSender();    //위에서 받은 매칭의 sender 받아와
            senders.add(sender);
        }

        List<GetUserRes> receivedUserRes = senders.stream()
                .map(user -> new GetUserRes(user.getGender(), user.getNickname(), user.getBirth(), user.getRegion(), user.getIntroduce(),
                new GetS3Res(user.getProfile().getProfileUrl(), user.getProfile().getProfileFileName()))).collect(Collectors.toList());

        return receivedUserRes;    //sender 목록 반환
    }


    //매칭된 친구 조회
    public List<GetUserRes> getMatchedFriends(Long userId) throws BaseException {
        User me = userRepository.findUserById(userId).orElse(null);   //id로 user객체 가져와
        List<Matching> receivedmatchings = matchingRequestRepository.findMatchingByReceiver(me).get();  // receiver가 '나'인 매칭 조회
        User friend;
        List<User> friends = new ArrayList<>();

        for(Matching matching : receivedmatchings){
            if(matching.isSuccess() == true){   //매칭의 success가 true이면
                friend = matching.getSender();  //친구 목록에 추가
                friends.add(friend);
            }
        }

        if(friends.isEmpty()){
            throw new BaseException(NONE_FREIND);
        }

        List<GetUserRes> friendResList = friends.stream()
                .map(user -> new GetUserRes(user.getGender(), user.getNickname(), user.getBirth(), user.getRegion(), user.getIntroduce(),
                        new GetS3Res(user.getProfile().getProfileUrl(), user.getProfile().getProfileFileName()))).collect(Collectors.toList());

        return friendResList;    //친구 목록 반환
    }

}
